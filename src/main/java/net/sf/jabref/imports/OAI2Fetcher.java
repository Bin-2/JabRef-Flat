/*  Copyright (C) 2003-2012 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package net.sf.jabref.imports;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.sf.jabref.*;
import net.sf.jabref.net.URLDownload;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 *
 * This class can be used to access any archive offering an OAI2 interface. By
 * default it will access ArXiv.org
 *
 * @author Ulrich St&auml;rk
 * @author Christian Kopf
 *
 * @version $Revision$ ($Date$)
 *
 */
public class OAI2Fetcher implements EntryFetcher {

    public static final String OAI2_ARXIV_HOST = "export.arxiv.org";
    public static final String OAI2_ARXIV_SCRIPT = "oai2";
    public static final String OAI2_ARXIV_METADATAPREFIX = "arXiv";
    public static final String OAI2_ARXIV_ARCHIVENAME = "ArXiv.org";
    public static final String OAI2_IDENTIFIER_FIELD = "oai2identifier";
    private static final String IDENTIFIER_PREFIX = "oai:arXiv.org:";

    private static final long ARXIV_MIN_REQUEST_INTERVAL_MS = 3_000L;
    private static final Object ARXIV_RATE_LOCK = new Object();
    private static long lastArxivRequestTime;

    private SAXParserFactory parserFactory;
    private SAXParser saxParser;

    private String oai2Host;
    private String oai2Script;
    private String oai2MetaDataPrefix;
    private String oai2ArchiveName;

    private boolean shouldContinue = true;
    private OutputPrinter status;

    /**
     * Minimum spacing between requests. The default arXiv fetcher uses the
     * three-second interval recommended by the arXiv API documentation.
     */
    private long waitTime = ARXIV_MIN_REQUEST_INTERVAL_MS;
    private long lastCallTime;

    public OAI2Fetcher(String oai2Host, String oai2Script, String oai2Metadataprefix,
            String oai2ArchiveName, long waitTimeMs) {
        this.oai2Host = oai2Host;
        this.oai2Script = oai2Script;
        this.oai2MetaDataPrefix = oai2Metadataprefix;
        this.oai2ArchiveName = oai2ArchiveName;
        this.waitTime = waitTimeMs;
        try {
            parserFactory = SAXParserFactory.newInstance();
            parserFactory.setValidating(false);
            configureSecureXmlParser(parserFactory);
            saxParser = parserFactory.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        }
    }

    /**
     * Default: ArXiv.org
     */
    public OAI2Fetcher() {
        this(OAI2_ARXIV_HOST, OAI2_ARXIV_SCRIPT, OAI2_ARXIV_METADATAPREFIX,
                OAI2_ARXIV_ARCHIVENAME, ARXIV_MIN_REQUEST_INTERVAL_MS);
    }

    private static void configureSecureXmlParser(SAXParserFactory factory) {
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureQuietly(factory, "http://xml.org/sax/features/validation", false);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    }

    private static void setFeatureQuietly(SAXParserFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | SAXException ignored) {
            // Some Java 8 XML providers do not support every hardening feature.
        }
    }

    private boolean isArxivArchive() {
        return OAI2_ARXIV_HOST.equalsIgnoreCase(oai2Host);
    }

    /**
     * Pace requests at the fetcher boundary so all arXiv OAI requests, not only
     * processQuery(), obey the same delay. arXiv requests are synchronized
     * across fetcher instances.
     */
    private boolean paceRequest() throws IOException {
        if (waitTime <= 0L) {
            return shouldContinue;
        }

        if (isArxivArchive()) {
            synchronized (ARXIV_RATE_LOCK) {
                if (!waitForRequestWindow(lastArxivRequestTime)) {
                    return false;
                }
                lastArxivRequestTime = System.currentTimeMillis();
            }
        } else {
            synchronized (this) {
                if (!waitForRequestWindow(lastCallTime)) {
                    return false;
                }
                lastCallTime = System.currentTimeMillis();
            }
        }
        return true;
    }

    private boolean waitForRequestWindow(long lastRequestTime) throws IOException {
        while (shouldContinue) {
            long elapsed = System.currentTimeMillis() - lastRequestTime;
            long remaining = waitTime - elapsed;
            if ((lastRequestTime == 0L) || (remaining <= 0L)) {
                return true;
            }

            if (status != null) {
                status.setStatus(Globals.lang("Waiting for ArXiv...") + " "
                        + Math.max(1L, (remaining + 999L) / 1000L) + " s");
            }

            try {
                Thread.sleep(Math.min(250L, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to contact " + oai2ArchiveName, e);
            }
        }
        return false;
    }

    private String downloadOaiXml(String url) throws IOException {
        IOException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!paceRequest()) {
                return null;
            }
            try {
                return new URLDownload(new URL(url))
                        .setRequestProperty("Accept", "application/xml, text/xml;q=0.9, */*;q=0.1")
                        .downloadToString("UTF-8");
            } catch (IOException e) {
                if ((attempt == 0) && isArxivArchive() && isHttp503(e)) {
                    firstFailure = e;
                    if (status != null) {
                        status.setStatus(Globals.lang("ArXiv is temporarily unavailable; retrying once..."));
                    }
                    continue;
                }
                throw e;
            }
        }
        throw firstFailure == null ? new IOException("Unable to contact " + oai2ArchiveName) : firstFailure;
    }

    private static boolean isHttp503(IOException e) {
        String message = e.getMessage();
        return (message != null) && message.startsWith("HTTP 503");
    }

    /**
     * Construct the query URL for GetRecord. Encode parameters, not the whole
     * URL; constants remain un-encoded.
     */
    public String constructUrl(String normalizedKey) {
        try {
            String identifier = IDENTIFIER_PREFIX + normalizedKey;
            String encIdentifier = URLEncoder.encode(identifier, "UTF-8");
            String encPrefix = URLEncoder.encode(oai2MetaDataPrefix, "UTF-8");
            return "https://" + oai2Host + "/" + oai2Script
                    + "?verb=GetRecord"
                    + "&identifier=" + encIdentifier
                    + "&metadataPrefix=" + encPrefix;
        } catch (UnsupportedEncodingException e) {
            // UTF-8 always present
            return "";
        }
    }

    /**
     * Remove "arxiv:" (case-insensitive), strip trailing version "v\\d+", then
     * apply legacy fixKey. New normalizer 09:46 2026-04-16
     */
    private static String normalizeArxivKey(String raw) {
        if (raw == null) {
            return null;
        }

        String k = raw.trim();
        if (k.length() == 0) {
            return "";
        }

        // Extract arXiv id from common URLs:
        //   https://arxiv.org/abs/1706.03762
        //   https://arxiv.org/abs/1706.03762v2
        //   https://arxiv.org/pdf/1706.03762.pdf
        //   https://arxiv.org/pdf/1706.03762v2.pdf
        //   https://arxiv.org/abs/hep-th/9901001
        //   https://arxiv.org/pdf/hep-th/9901001v2.pdf
        k = k.replace('\\', '/');

        int p;
        p = k.indexOf("arxiv.org/abs/");
        if (p >= 0) {
            k = k.substring(p + "arxiv.org/abs/".length());
        } else {
            p = k.indexOf("arxiv.org/pdf/");
            if (p >= 0) {
                k = k.substring(p + "arxiv.org/pdf/".length());
            } else {
                p = k.indexOf("export.arxiv.org/abs/");
                if (p >= 0) {
                    k = k.substring(p + "export.arxiv.org/abs/".length());
                } else {
                    p = k.indexOf("export.arxiv.org/pdf/");
                    if (p >= 0) {
                        k = k.substring(p + "export.arxiv.org/pdf/".length());
                    }
                }
            }
        }

        // Drop query string or fragment
        int q = k.indexOf('?');
        if (q >= 0) {
            k = k.substring(0, q);
        }
        int h = k.indexOf('#');
        if (h >= 0) {
            k = k.substring(0, h);
        }

        // Drop trailing .pdf for pdf URLs
        if (k.toLowerCase().endsWith(".pdf")) {
            k = k.substring(0, k.length() - 4);
        }

        // Remove leading arXiv: prefix if present
        if (k.regionMatches(true, 0, "arxiv:", 0, 6)) {
            k = k.substring(6).trim();
        }

        // Strip version suffix
        k = k.replaceFirst("v\\d+$", "");

        // Legacy normalization for old category ids
        return fixKey(k);
    }

    /**
     * Strip subcategories from ArXiv key.
     *
     * @param key The key to fix.
     * @return Fixed key.
     */
    public static String fixKey(String key) {
        if (key.toLowerCase().startsWith("arxiv:")) {
            key = key.substring(6);
        }
        int dot = key.indexOf('.');
        int slash = key.indexOf('/');
        if (dot > -1 && slash > -1 && dot < slash) {
            key = key.substring(0, dot) + key.substring(slash);
        }
        return key;
    }

    public static String correctLineBreaks(String s) {
        s = s.replaceAll("\\n(?!\\s*\\n)", " ");
        s = s.replaceAll("\\s*\\n\\s*", "\n");
        return s.replaceAll(" {2,}", " ").replaceAll("(^\\s*|\\s+$)", "");
    }

    /**
     * Import an entry from an OAI2 archive. The BibtexEntry provided has to
     * have the field OAI2_IDENTIFIER_FIELD set to the search string.
     *
     * @param key The OAI2 key to fetch from ArXiv.
     * @return The imported BibtexEntry or null if none.
     */
    public BibtexEntry importOai2Entry(String rawKey) {
        String key = normalizeArxivKey(rawKey);
        if ((key == null) || key.trim().isEmpty()) {
            return null;
        }

        String url = constructUrl(key);
        try {
            String xml = downloadOaiXml(url);
            if (xml == null) {
                return null;
            }
            if (saxParser == null) {
                throw new SAXException("XML parser is not available");
            }

            // Use the currently active Article definition, including BibLaTeX
            // or user-customized definitions.
            BibtexEntryType articleType = BibtexEntryType.getType("article");
            if (articleType == null) {
                articleType = BibtexEntryType.ARTICLE;
            }

            BibtexEntry be = new BibtexEntry(Util.createNeutralId(), articleType);
            be.setField(OAI2_IDENTIFIER_FIELD, key);

            DefaultHandler handlerBase = new OAI2Handler(be);
            InputSource inputSource = new InputSource(new StringReader(xml));
            saxParser.parse(inputSource, handlerBase);

            // Normalize whitespace in all fields.
            for (String name : be.getAllFields()) {
                String value = be.getField(name);
                if (value != null) {
                    be.setField(name, correctLineBreaks(value));
                }
            }

            // Build arXiv URL and e-print fields from the normalized key.
            String arxivId = key;
            be.setField("url", "https://arxiv.org/abs/" + arxivId);
            be.setField("eprint", arxivId);
            be.setField("eprinttype", "arxiv");

            // Keep any DOI supplied by arXiv metadata. Otherwise use arXiv's
            // canonical DataCite DOI for the e-print.
            String existingDoi = be.getField("doi");
            if ((existingDoi == null) || existingDoi.trim().isEmpty()) {
                be.setField("doi", "10.48550/arXiv." + arxivId);
            }

            be.setField("journal", "arXiv preprint arXiv:" + arxivId);

            // Infer year/month for new-style ids (yymm.nnnnn).
            if (key.matches("\\d\\d\\d\\d\\..*")) {
                be.setField("year", "20" + key.substring(0, 2));
                int monthNumber = Integer.parseInt(key.substring(2, 4));
                MonthUtil.Month month = MonthUtil.getMonthByNumber(monthNumber);
                if (month.isValid()) {
                    be.setField("month", month.bibtexFormat);
                }
            }

            // This timestamp is local JabRef metadata: when the entry was fetched.
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
            be.setField("timestamp", fmt.format(new Date()));

            return be;
        } catch (IOException e) {
            if (status != null) {
                status.showMessage(Globals.lang("An exception occurred while accessing '%0'", url)
                        + "\n\n" + e.toString(), Globals.lang(getKeyName()), JOptionPane.ERROR_MESSAGE);
            }
        } catch (SAXException e) {
            if (status != null) {
                status.showMessage(Globals.lang("A SAXException occurred while parsing '%0':", new String[]{url})
                        + "\n\n" + e.getMessage(), Globals.lang(getKeyName()), JOptionPane.ERROR_MESSAGE);
            }
        } catch (RuntimeException e) {
            if (status != null) {
                status.showMessage(Globals.lang("An error occurred while fetching from OAI2 source (%0):", new String[]{url})
                        + "\n\n" + e.getMessage(), Globals.lang(getKeyName()), JOptionPane.ERROR_MESSAGE);
            }
        }
        return null;
    }

    @Override
    public String getHelpPage() {
        return null;
    }

    @Override
    public URL getIcon() {
        return GUIGlobals.getIconUrl("www");
    }

    @Override
    public String getKeyName() {
        return oai2ArchiveName;
    }

    @Override
    public JPanel getOptionsPanel() {
        return null;
    }

    @Override
    public String getTitle() {
        return Globals.menuTitle(getKeyName());
    }

    @Override
    public boolean processQuery(String query, ImportInspector dialog, OutputPrinter status) {
        this.status = status;
        try {
            shouldContinue = true;

            // accept ; or space between keys
            String[] keys = query.replace(' ', ';').split(";");
            for (int i = 0; i < keys.length; i++) {
                String key = keys[i].trim();
                if (key.isEmpty()) {
                    continue;
                }

                if (!shouldContinue) {
                    break;
                }

                status.setStatus(Globals.lang("Processing ") + key);
                BibtexEntry be = importOai2Entry(key);

                if (be != null) {
                    dialog.addEntry(be);
                }
                dialog.setProgress(i + 1, keys.length);
            }
            return true;
        } catch (Exception e) {
            status.setStatus(Globals.lang("Error while fetching from OAI2") + ": " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void stopFetching() {
        shouldContinue = false;
    }
}
