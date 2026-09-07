/*  Copyright (C) 2003-2015 JabRef contributors.
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JPanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexEntryType;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.OutputPrinter;
import net.sf.jabref.net.URLDownload;

/**
 * Searches Crossref's public REST API and maps returned metadata to legacy
 * JabRef BibtexEntry objects.
 */
public class CrossrefFetcher implements EntryFetcher {

    private static final String SEARCH_URL = "https://api.crossref.org/works";
    private static final int MAX_RESULTS = 30;

    /**
     * Crossref work types that represent useful bibliography entries in an
     * interactive search. Provider-level objects such as components, journal
     * issues/volumes, peer reviews, grants and databases are intentionally
     * excluded.
     */
    private static final String SEARCH_TYPE_FILTER =
            "type:journal-article"
            + ",type:proceedings-article"
            + ",type:proceedings"
            + ",type:book"
            + ",type:monograph"
            + ",type:edited-book"
            + ",type:reference-book"
            + ",type:book-chapter"
            + ",type:book-section"
            + ",type:dissertation"
            + ",type:report"
            + ",type:standard"
            + ",type:posted-content"
            + ",type:dataset";

    private volatile boolean shouldContinue;

    @Override
    public void stopFetching() {
        shouldContinue = false;
    }

    @Override
    public boolean processQuery(String query, ImportInspector inspector,
            OutputPrinter status) {

        if ((query == null) || query.trim().isEmpty()) {
            status.showMessage("Please enter a Crossref search query.");
            return false;
        }

        shouldContinue = true;

        try {
            String doi = extractDoi(query);
            if (doi != null) {
                return fetchExactDoi(doi, inspector, status);
            }

            return searchBibliographic(query, inspector, status);

        } catch (IOException e) {
            status.showMessage("Crossref search failed: " + safeMessage(e));
            return false;
        } catch (RuntimeException e) {
            status.showMessage("Could not parse the response from Crossref: " + safeMessage(e));
            return false;
        }
    }

    private boolean fetchExactDoi(String doi, ImportInspector inspector,
            OutputPrinter status) throws IOException {
        URL url = makeDoiUrl(doi);
        String response = downloadJson(url);

        if (!shouldContinue) {
            return false;
        }

        JsonObject root = new JsonParser().parse(response).getAsJsonObject();
        JsonObject message = getObject(root, "message");
        if (message == null) {
            status.showMessage("Crossref returned an unexpected response.");
            return false;
        }

        BibtexEntry entry = toBibtexEntry(message);
        if (entry == null) {
            status.showMessage("Crossref returned no usable bibliographic metadata for this DOI.");
            return false;
        }

        inspector.setProgress(1, 1);
        inspector.addEntry(entry);
        return true;
    }

    private boolean searchBibliographic(String query, ImportInspector inspector,
            OutputPrinter status) throws IOException {
        URL url = makeSearchUrl(query);
        String response = downloadJson(url);

        if (!shouldContinue) {
            return false;
        }

        JsonObject root = new JsonParser().parse(response).getAsJsonObject();
        JsonObject message = getObject(root, "message");
        JsonArray items = (message == null) ? null : getArray(message, "items");
        if (items == null) {
            status.showMessage("Crossref returned an unexpected response.");
            return false;
        }

        Set<String> seenDois = new HashSet<String>();
        int total = items.size();
        int processed = 0;

        for (JsonElement element : items) {
            if (!shouldContinue) {
                break;
            }
            processed++;
            inspector.setProgress(processed, total);

            if ((element == null) || !element.isJsonObject()) {
                continue;
            }

            JsonObject item = element.getAsJsonObject();
            if (!isAllowedSearchType(getString(item, "type"))) {
                continue;
            }

            BibtexEntry entry = toBibtexEntry(item);
            if (entry == null) {
                continue;
            }

            String doi = entry.getField("doi");
            if ((doi != null) && !seenDois.add(doi.toLowerCase())) {
                continue;
            }

            inspector.addEntry(entry);
        }

        return true;
    }

    private static String downloadJson(URL url) throws IOException {
        return new URLDownload(url)
                .setRequestProperty("Accept", "application/json")
                .downloadToString("UTF-8");
    }

    private static URL makeSearchUrl(String query) throws IOException {
        StringBuilder address = new StringBuilder(SEARCH_URL);
        address.append("?query.bibliographic=")
                .append(URLEncoder.encode(query.trim(), "UTF-8"));
        address.append("&filter=")
                .append(URLEncoder.encode(SEARCH_TYPE_FILTER, "UTF-8"));
        address.append("&rows=").append(MAX_RESULTS);
        return new URL(address.toString());
    }

    private static URL makeDoiUrl(String doi) throws IOException {
        try {
            // URI's path constructor escapes spaces, #, ?, etc. while keeping
            // DOI path separators intact, which is appropriate for /works/{doi}.
            return new URI("https", "api.crossref.org", "/works/" + doi, null).toURL();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid DOI: " + doi, e);
        }
    }

    private static String extractDoi(String query) {
        if (query == null) {
            return null;
        }

        String value = query.trim();
        if (value.regionMatches(true, 0, "doi:", 0, 4)) {
            value = value.substring(4).trim();
        }
        value = normalizeDoi(value);

        // DOI Handbook syntax in practical form: directory indicator 10,
        // registrant code of 4-9 digits, slash, then a non-whitespace suffix.
        if (!value.matches("(?i)^10\\.\\d{4,9}/\\S+$")) {
            return null;
        }

        // Trailing sentence punctuation is commonly included when users paste
        // a DOI from prose, but is not normally part of the identifier.
        while (value.endsWith(".") || value.endsWith(",") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isAllowedSearchType(String crossrefType) {
        return "journal-article".equals(crossrefType)
                || "proceedings-article".equals(crossrefType)
                || "proceedings".equals(crossrefType)
                || "book".equals(crossrefType)
                || "monograph".equals(crossrefType)
                || "edited-book".equals(crossrefType)
                || "reference-book".equals(crossrefType)
                || "book-chapter".equals(crossrefType)
                || "book-section".equals(crossrefType)
                || "dissertation".equals(crossrefType)
                || "report".equals(crossrefType)
                || "standard".equals(crossrefType)
                || "posted-content".equals(crossrefType)
                || "dataset".equals(crossrefType);
    }

    private static BibtexEntry toBibtexEntry(JsonObject item) {
        String title = cleanMarkup(firstString(item, "title"));
        String subtitle = cleanMarkup(firstString(item, "subtitle"));
        if (!isEmpty(subtitle)) {
            title = isEmpty(title) ? subtitle : title + ": " + subtitle;
        }
        if (isEmpty(title)) {
            return null;
        }

        String crossrefType = getString(item, "type");
        BibtexEntryType type = mapType(crossrefType);
        BibtexEntry entry = new BibtexEntry();
        entry.setType(type);

        put(entry, "title", title);
        put(entry, "author", formatPersons(getArray(item, "author")));
        put(entry, "editor", formatPersons(getArray(item, "editor")));

        String containerTitle = firstString(item, "container-title");
        if (type == BibtexEntryType.ARTICLE || type == BibtexEntryType.PERIODICAL) {
            put(entry, "journal", containerTitle);
        } else if (type == BibtexEntryType.INPROCEEDINGS
                || type == BibtexEntryType.PROCEEDINGS
                || type == BibtexEntryType.INCOLLECTION
                || type == BibtexEntryType.INBOOK) {
            put(entry, "booktitle", containerTitle);
        } else if (!isEmpty(containerTitle)) {
            put(entry, "journal", containerTitle);
        }

        put(entry, "publisher", getString(item, "publisher"));
        if (type == BibtexEntryType.TECHREPORT) {
            put(entry, "institution", getString(item, "publisher"));
        }

        put(entry, "volume", getString(item, "volume"));
        put(entry, "number", getString(item, "issue"));
        put(entry, "pages", getString(item, "page"));
        put(entry, "eid", getString(item, "article-number"));
        put(entry, "doi", normalizeDoi(getString(item, "DOI")));
        put(entry, "url", getString(item, "URL"));
        put(entry, "issn", joinStrings(getArray(item, "ISSN"), ", "));
        put(entry, "isbn", joinStrings(getArray(item, "ISBN"), ", "));
        put(entry, "language", getString(item, "language"));
        put(entry, "keywords", joinStrings(getArray(item, "subject"), "; "));

        String abstractText = getString(item, "abstract");
        put(entry, "abstract", cleanMarkup(abstractText));

        addPublicationDate(entry, item);
        return entry;
    }

    private static BibtexEntryType mapType(String crossrefType) {
        if ("journal-article".equals(crossrefType)) {
            return BibtexEntryType.ARTICLE;
        }
        if ("proceedings-article".equals(crossrefType)) {
            return BibtexEntryType.INPROCEEDINGS;
        }
        if ("proceedings".equals(crossrefType)) {
            return BibtexEntryType.PROCEEDINGS;
        }
        if ("book".equals(crossrefType) || "monograph".equals(crossrefType)
                || "reference-book".equals(crossrefType)) {
            return BibtexEntryType.BOOK;
        }
        if ("book-chapter".equals(crossrefType)
                || "book-section".equals(crossrefType)
                || "reference-entry".equals(crossrefType)) {
            return BibtexEntryType.INCOLLECTION;
        }
        if ("dissertation".equals(crossrefType)) {
            return BibtexEntryType.PHDTHESIS;
        }
        if ("report".equals(crossrefType) || "report-series".equals(crossrefType)) {
            return BibtexEntryType.TECHREPORT;
        }
        if ("journal".equals(crossrefType) || "journal-volume".equals(crossrefType)
                || "journal-issue".equals(crossrefType)) {
            return BibtexEntryType.PERIODICAL;
        }
        if ("standard".equals(crossrefType)) {
            return BibtexEntryType.STANDARD;
        }
        if ("posted-content".equals(crossrefType)) {
            return BibtexEntryType.UNPUBLISHED;
        }
        return BibtexEntryType.MISC;
    }

    private static void addPublicationDate(BibtexEntry entry, JsonObject item) {
        int[] parts = dateParts(item, "published-print");
        if (parts == null) {
            parts = dateParts(item, "published-online");
        }
        if (parts == null) {
            parts = dateParts(item, "issued");
        }
        if ((parts == null) || (parts.length == 0)) {
            return;
        }

        if (parts[0] > 0) {
            entry.setField("year", Integer.toString(parts[0]));
        }
        if ((parts.length > 1) && (parts[1] >= 1) && (parts[1] <= 12)) {
            entry.setField("month", monthName(parts[1]));
        }
    }

    private static int[] dateParts(JsonObject item, String name) {
        JsonObject date = getObject(item, name);
        JsonArray dateParts = (date == null) ? null : getArray(date, "date-parts");
        if ((dateParts == null) || (dateParts.size() == 0)
                || !dateParts.get(0).isJsonArray()) {
            return null;
        }

        JsonArray values = dateParts.get(0).getAsJsonArray();
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            try {
                result[i] = values.get(i).getAsInt();
            } catch (RuntimeException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static String monthName(int month) {
        final String[] names = {
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"
        };
        return names[month - 1];
    }

    private static String formatPersons(JsonArray persons) {
        if (persons == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (JsonElement element : persons) {
            if ((element == null) || !element.isJsonObject()) {
                continue;
            }
            JsonObject person = element.getAsJsonObject();
            String family = getString(person, "family");
            String given = getString(person, "given");
            String literal = getString(person, "name");

            String formatted;
            if (!isEmpty(family)) {
                formatted = isEmpty(given) ? family : family + ", " + given;
            } else if (!isEmpty(literal)) {
                formatted = literal;
            } else if (!isEmpty(given)) {
                formatted = given;
            } else {
                continue;
            }

            if (result.length() > 0) {
                result.append(" and ");
            }
            result.append(formatted);
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static String firstString(JsonObject object, String name) {
        JsonArray values = getArray(object, name);
        if ((values == null) || (values.size() == 0)) {
            return null;
        }
        return stringValue(values.get(0));
    }

    private static String joinStrings(JsonArray values, String separator) {
        if (values == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (JsonElement value : values) {
            String text = stringValue(value);
            if (isEmpty(text)) {
                continue;
            }
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(text);
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static String getString(JsonObject object, String name) {
        if ((object == null) || !object.has(name)) {
            return null;
        }
        return stringValue(object.get(name));
    }

    private static String stringValue(JsonElement value) {
        if ((value == null) || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject getObject(JsonObject object, String name) {
        if ((object == null) || !object.has(name)) {
            return null;
        }
        JsonElement value = object.get(name);
        return (value != null) && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray getArray(JsonObject object, String name) {
        if ((object == null) || !object.has(name)) {
            return null;
        }
        JsonElement value = object.get(name);
        return (value != null) && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static void put(BibtexEntry entry, String field, String value) {
        if (!isEmpty(value)) {
            entry.setField(field, value.trim());
        }
    }

    private static String normalizeDoi(String doi) {
        if (doi == null) {
            return null;
        }
        String value = doi.trim();
        if (value.regionMatches(true, 0, "https://doi.org/", 0, 16)) {
            value = value.substring(16);
        } else if (value.regionMatches(true, 0, "http://doi.org/", 0, 15)) {
            value = value.substring(15);
        }
        return value;
    }

    private static String cleanMarkup(String text) {
        if (isEmpty(text)) {
            return null;
        }
        return text.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isEmpty(String value) {
        return (value == null) || value.trim().isEmpty();
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return ((message == null) || message.trim().isEmpty())
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Override
    public String getTitle() {
        return "Crossref";
    }

    @Override
    public String getKeyName() {
        return "Crossref";
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
