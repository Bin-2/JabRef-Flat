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
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.sf.jabref.*;

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

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private SAXParserFactory parserFactory;
    private SAXParser saxParser;

    private String oai2Host;
    private String oai2Script;
    private String oai2MetaDataPrefix;
    private String oai2ArchiveName;

    private boolean shouldContinue = true;
    private OutputPrinter status;

    /**
     * spacing between calls; arXiv is conservative
     */
    private long waitTime = 20_000L;
    private Date lastCall;

    public OAI2Fetcher(String oai2Host, String oai2Script, String oai2Metadataprefix,
            String oai2ArchiveName, long waitTimeMs) {
        this.oai2Host = oai2Host;
        this.oai2Script = oai2Script;
        this.oai2MetaDataPrefix = oai2Metadataprefix;
        this.oai2ArchiveName = oai2ArchiveName;
        this.waitTime = waitTimeMs;
        try {
            parserFactory = SAXParserFactory.newInstance();
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
                OAI2_ARXIV_ARCHIVENAME, 20_000L);
    }

    private boolean shouldWait() {
        return waitTime > 0;
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
     * apply legacy fixKey.
     */
    private static String normalizeArxivKey(String raw) {
        String k = raw.trim();
        if (k.regionMatches(true, 0, "arxiv:", 0, 6)) {
            k = k.substring(6).trim();
        }
        // Strip version suffix (e.g., 1234.5678v2 -> 1234.5678)
        k = k.replaceFirst("v\\d+$", "");
        // Legacy normalization (keeps old category scheme behavior)
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
        String url = constructUrl(key);

        HttpURLConnection conn = null;
        try {
            URL oai2Url = new URL(url);
            conn = (HttpURLConnection) oai2Url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/xml, text/xml;q=0.9, */*;q=0.8");
            conn.setRequestProperty("User-Agent", "OAI2Fetcher/2.0 (+your-email-or-site)");

            // Handle 503 Retry-After (arXiv rate-limit)
            int code = conn.getResponseCode();
            if (code == 503) {
                String retry = conn.getHeaderField("Retry-After");
                int waitSec = 0;
                if (retry != null) {
                    try {
                        waitSec = Integer.parseInt(retry.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (waitSec > 0) {
                    if (status != null) {
                        status.setStatus("Server asked to wait " + waitSec + " s (Retry-After).");
                    }
                    try {
                        Thread.sleep(waitSec * 1000L);
                    } catch (InterruptedException ignored) {
                    }
                    conn.disconnect();
                    conn = (HttpURLConnection) oai2Url.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/xml, text/xml;q=0.9, */*;q=0.8");
                    conn.setRequestProperty("User-Agent", "OAI2Fetcher/2.0 (+your-email-or-site)");
                }
            }

            // Success path
            code = conn.getResponseCode();
            if (code != 200) {
                if (status != null) {
                    status.showMessage(Globals.lang("OAI2 request failed: HTTP %0", String.valueOf(code))
                            + "\n" + url, Globals.lang(getKeyName()), JOptionPane.ERROR_MESSAGE);
                }
                return null;
            }

            try (InputStream inputStream = conn.getInputStream()) {
                // Create BibTeX entry and seed the identifier
                BibtexEntry be = new BibtexEntry(Util.createNeutralId(), BibtexEntryType.ARTICLE);
                be.setField(OAI2_IDENTIFIER_FIELD, key);

                DefaultHandler handlerBase = new OAI2Handler(be); // your existing handler
                saxParser.parse(inputStream, handlerBase);

                // Normalize whitespace in all fields
                for (String name : be.getAllFields()) {
                    String v = be.getField(name);
                    if (v != null) {
                        be.setField(name, correctLineBreaks(v));
                    }
                }

                // Build arXiv URL and journal fields from the normalized key
                // Assumption: 'key' is the normalized ID used in constructUrl() (no "arXiv:" prefix, no trailing vN)
                String arxivId = key;

                // URL: arXiv abstract page; supports both new and legacy IDs
                String arxivAbsUrl = "https://arxiv.org/abs/" + arxivId;
                be.setField("url", arxivAbsUrl);
                be.setField("eprint", arxivId);
                be.setField("eprinttype", "arxiv");

                // Journal: requested pattern "arXiv preprint arXiv:<ID>"
                be.setField("journal", "arXiv preprint arXiv:" + arxivId);

                // Infer year/month for new-style ids (yymm.nnnnn)
                if (key.matches("\\d\\d\\d\\d\\..*")) {
                    be.setField("year", "20" + key.substring(0, 2));
                    int monthNumber = Integer.parseInt(key.substring(2, 4));
                    MonthUtil.Month month = MonthUtil.getMonthByNumber(monthNumber);
                    if (month.isValid()) {
                        be.setField("month", month.bibtexFormat);
                    }
                }

                // Add timestamp field
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
                be.setField("timestamp", fmt.format(new Date()));

                return be;
            }
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
        } finally {
            if (conn != null) {
                conn.disconnect();
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

                // Polite delay between calls if configured
                if (shouldWait() && lastCall != null) {
                    long elapsed = System.currentTimeMillis() - lastCall.getTime();
                    while (elapsed < waitTime && shouldContinue) {
                        long remain = waitTime - elapsed;
                        status.setStatus(Globals.lang("Waiting for ArXiv...") + (remain / 1000) + " s");
                        try {
                            Thread.sleep(Math.min(1000, remain));
                        } catch (InterruptedException ignored) {
                        }
                        elapsed = System.currentTimeMillis() - lastCall.getTime();
                    }
                }

                if (!shouldContinue) {
                    break;
                }

                status.setStatus(Globals.lang("Processing ") + key);
                BibtexEntry be = importOai2Entry(key);

                if (shouldWait()) {
                    lastCall = new Date();
                }

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
