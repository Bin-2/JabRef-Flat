package net.sf.jabref.imports;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.OutputPrinter;
import net.sf.jabref.net.URLDownload;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Web Search fetcher for the arXiv Atom API.
 *
 * Exact arXiv identifiers are sent through id_list. Other input is treated as
 * an arXiv search query. Simple free text is expanded to an AND search across
 * all fields, while native arXiv query syntax is passed through unchanged.
 */
public class ArxivFetcher implements EntryFetcher {

    private static final String API_URL = "https://export.arxiv.org/api/query";
    private static final int SEARCH_RESULT_LIMIT = 30;
    private static final int ID_RESULT_LIMIT = 100;
    private static final long MIN_REQUEST_INTERVAL_MS = 3_000L;

    private static final Object RATE_LOCK = new Object();
    private static long lastRequestTime;

    private volatile boolean shouldContinue = true;
    private OutputPrinter status;
    private final SAXParserFactory parserFactory;

    public ArxivFetcher() {
        parserFactory = SAXParserFactory.newInstance();
        parserFactory.setValidating(false);
        parserFactory.setNamespaceAware(true);
        configureSecureXmlParser(parserFactory);
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

    @Override
    public boolean processQuery(String query, ImportInspector inspector, OutputPrinter status) {
        this.status = status;
        shouldContinue = true;

        String input = query == null ? "" : query.trim();
        if (input.isEmpty()) {
            status.setStatus(Globals.lang("Please enter a search string"));
            return false;
        }

        try {
            List<String> ids = parseIdentifierList(input);
            String requestUrl;
            if (ids != null) {
                requestUrl = buildIdListUrl(ids);
                status.setStatus(Globals.lang("Fetching ArXiv entries..."));
            } else {
                requestUrl = buildSearchUrl(input);
                status.setStatus(Globals.lang("Searching ArXiv..."));
            }

            if (!paceRequest()) {
                return false;
            }

            String xml = new URLDownload(new URL(requestUrl))
                    .setRequestProperty("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.1")
                    .downloadToString("UTF-8");

            if (!shouldContinue) {
                return false;
            }

            ArxivAtomHandler handler = parse(xml);
            if (handler.getErrorMessage() != null) {
                status.showMessage(handler.getErrorMessage(), Globals.lang("ArXiv.org"), JOptionPane.ERROR_MESSAGE);
                return false;
            }

            List<BibtexEntry> entries = handler.getEntries();
            int total = entries.size();
            for (int i = 0; (i < total) && shouldContinue; i++) {
                inspector.addEntry(entries.get(i));
                inspector.setProgress(i + 1, total);
            }

            if (total == 0) {
                status.setStatus(Globals.lang("No entries found"));
            } else {
                status.setStatus(Globals.lang("Fetched %0 entries", Integer.toString(total)));
            }
            return shouldContinue;
        } catch (IOException e) {
            showError(Globals.lang("An exception occurred while accessing ArXiv") + "\n\n" + e.getMessage());
        } catch (ParserConfigurationException | SAXException e) {
            showError(Globals.lang("An error occurred while parsing the ArXiv response") + "\n\n" + e.getMessage());
        } catch (RuntimeException e) {
            showError(Globals.lang("An error occurred while fetching from ArXiv") + "\n\n" + e.getMessage());
        }
        return false;
    }

    private ArxivAtomHandler parse(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        SAXParser parser = parserFactory.newSAXParser();
        ArxivAtomHandler handler = new ArxivAtomHandler();
        InputSource input = new InputSource(new StringReader(xml));
        input.setEncoding("UTF-8");
        parser.parse(input, handler);
        return handler;
    }

    private boolean paceRequest() throws IOException {
        synchronized (RATE_LOCK) {
            while (shouldContinue) {
                long elapsed = System.currentTimeMillis() - lastRequestTime;
                long remaining = MIN_REQUEST_INTERVAL_MS - elapsed;
                if ((lastRequestTime == 0L) || (remaining <= 0L)) {
                    lastRequestTime = System.currentTimeMillis();
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
                    throw new IOException("Interrupted while waiting to contact arXiv", e);
                }
            }
        }
        return false;
    }

    static String buildSearchUrl(String query) throws UnsupportedEncodingException {
        String arxivQuery = isNativeSearchQuery(query) ? query.trim() : buildFreeTextQuery(query);
        return API_URL
                + "?search_query=" + encode(arxivQuery)
                + "&start=0"
                + "&max_results=" + SEARCH_RESULT_LIMIT
                + "&sortBy=relevance"
                + "&sortOrder=descending";
    }

    static String buildIdListUrl(List<String> ids) throws UnsupportedEncodingException {
        int count = Math.min(ids.size(), ID_RESULT_LIMIT);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append(ids.get(i));
        }
        return API_URL
                + "?id_list=" + encode(list.toString())
                + "&start=0"
                + "&max_results=" + count;
    }

    /**
     * Return a normalized identifier list if every token is an arXiv id;
     * otherwise return null so the input can be treated as a text query.
     */
    static List<String> parseIdentifierList(String input) {
        String[] tokens = input.trim().split("[\\s,;]+");
        if (tokens.length == 0) {
            return null;
        }

        List<String> ids = new ArrayList<>();
        for (String token : tokens) {
            if (token.trim().isEmpty()) {
                continue;
            }
            String normalized = normalizeArxivId(token);
            if (!isArxivId(normalized)) {
                return null;
            }
            ids.add(normalized);
        }
        return ids.isEmpty() ? null : ids;
    }

    static String normalizeArxivId(String raw) {
        if (raw == null) {
            return "";
        }

        String value = raw.trim().replace('\\', '/');

        // Accept DataCite arXiv DOI forms as identifiers too.
        int dataCite = indexOfIgnoreCase(value, "10.48550/arxiv.");
        if (dataCite >= 0) {
            value = value.substring(dataCite + "10.48550/arxiv.".length());
        }

        int pos = indexOfIgnoreCase(value, "arxiv.org/abs/");
        if (pos >= 0) {
            value = value.substring(pos + "arxiv.org/abs/".length());
        } else {
            pos = indexOfIgnoreCase(value, "arxiv.org/pdf/");
            if (pos >= 0) {
                value = value.substring(pos + "arxiv.org/pdf/".length());
            }
        }

        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }

        if (value.toLowerCase().endsWith(".pdf")) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.regionMatches(true, 0, "arxiv:", 0, 6)) {
            value = value.substring(6);
        }

        // Old identifiers may include a subject subcategory.
        // arXiv id_list expects the archive form without
        // that subcategory, matching the normalization used by OAI2Fetcher.
        int dot = value.indexOf('.');
        int slash = value.indexOf('/');
        if ((dot > -1) && (slash > -1) && (dot < slash)) {
            value = value.substring(0, dot) + value.substring(slash);
        }

        return value.trim();
    }

    static boolean isArxivId(String value) {
        if ((value == null) || value.isEmpty()) {
            return false;
        }
        return value.matches("\\d{4}\\.\\d{4,5}(?i:v\\d+)?")
                || value.matches("[A-Za-z0-9.-]+/\\d{7}(?i:v\\d+)?");
    }

    private static boolean isNativeSearchQuery(String query) {
        String value = query == null ? "" : query;
        return value.matches("(?is).*\\b(all|ti|au|abs|co|jr|cat|rn|id):.*")
                || value.matches("(?is).*\\b(AND|OR|ANDNOT)\\b.*");
    }

    private static String buildFreeTextQuery(String query) {
        List<String> terms = splitSearchTerms(query);
        StringBuilder result = new StringBuilder();
        for (String term : terms) {
            if (result.length() > 0) {
                result.append(" AND ");
            }
            result.append("all:").append(term);
        }
        return result.length() == 0 ? "all:" + query.trim() : result.toString();
    }

    private static List<String> splitSearchTerms(String query) {
        List<String> terms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (Character.isWhitespace(c) && !quoted) {
                addTerm(terms, current);
            } else {
                current.append(c);
            }
        }
        addTerm(terms, current);
        return terms;
    }

    private static void addTerm(List<String> terms, StringBuilder current) {
        String term = current.toString().trim();
        if (!term.isEmpty()) {
            terms.add(term);
        }
        current.setLength(0);
    }

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase().indexOf(needle.toLowerCase());
    }

    private void showError(String message) {
        if (status != null) {
            status.showMessage(message, Globals.lang("ArXiv.org"), JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void stopFetching() {
        shouldContinue = false;
    }

    @Override
    public String getTitle() {
        return Globals.menuTitle(getKeyName());
    }

    @Override
    public String getKeyName() {
        return "ArXiv.org";
    }

    @Override
    public URL getIcon() {
        return GUIGlobals.getIconUrl("www");
    }

    @Override
    public String getHelpPage() {
        return null;
    }

    @Override
    public JPanel getOptionsPanel() {
        return null;
    }
}
