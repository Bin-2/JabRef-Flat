package net.sf.jabref.imports;

import java.util.ArrayList;
import java.util.List;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexEntryType;
import net.sf.jabref.MonthUtil;
import net.sf.jabref.Util;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SAX handler for the Atom 1.0 response returned by the arXiv query API.
 */
class ArxivAtomHandler extends DefaultHandler {

    private final List<BibtexEntry> entries = new ArrayList<>();

    private BibtexEntry currentEntry;
    private StringBuilder characters = new StringBuilder();
    private final List<String> authors = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();

    private String currentAuthor;
    private String entryId;
    private String published;
    private String updated;
    private String primaryCategory;
    private String journalReference;
    private String doi;
    private String errorTitle;
    private String errorSummary;
    private String errorMessage;

    public List<BibtexEntry> getEntries() {
        return entries;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public void startElement(String uri, String localName, String qualifiedName,
            Attributes attributes) throws SAXException {
        characters.setLength(0);
        String name = elementName(localName, qualifiedName);

        if ("entry".equals(name)) {
            BibtexEntryType articleType = BibtexEntryType.getType("article");
            if (articleType == null) {
                articleType = BibtexEntryType.ARTICLE;
            }
            currentEntry = new BibtexEntry(Util.createNeutralId(), articleType);
            authors.clear();
            categories.clear();
            currentAuthor = null;
            entryId = null;
            published = null;
            updated = null;
            primaryCategory = null;
            journalReference = null;
            doi = null;
            errorTitle = null;
            errorSummary = null;
        } else if ((currentEntry != null) && "category".equals(name)) {
            String term = attributes.getValue("term");
            if ((term != null) && !term.trim().isEmpty()) {
                categories.add(term.trim());
            }
        } else if ((currentEntry != null) && "primary_category".equals(name)) {
            String term = attributes.getValue("term");
            if ((term != null) && !term.trim().isEmpty()) {
                primaryCategory = term.trim();
            }
        } else if ((currentEntry != null) && "link".equals(name)) {
            String title = attributes.getValue("title");
            String href = attributes.getValue("href");
            if ((href != null) && "doi".equalsIgnoreCase(title)) {
                String linkDoi = normalizeDoi(href);
                if (!linkDoi.isEmpty()) {
                    doi = linkDoi;
                }
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        characters.append(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qualifiedName) throws SAXException {
        if (currentEntry == null) {
            return;
        }

        String name = elementName(localName, qualifiedName);
        String content = clean(characters.toString());

        if ("title".equals(name)) {
            currentEntry.setField("title", content);
            errorTitle = content;
        } else if ("id".equals(name)) {
            entryId = content;
        } else if ("published".equals(name)) {
            published = content;
        } else if ("updated".equals(name)) {
            updated = content;
        } else if ("summary".equals(name)) {
            currentEntry.setField("abstract", content);
            errorSummary = content;
        } else if ("name".equals(name)) {
            currentAuthor = content;
        } else if ("author".equals(name)) {
            if ((currentAuthor != null) && !currentAuthor.isEmpty()) {
                authors.add(currentAuthor);
            }
            currentAuthor = null;
        } else if ("comment".equals(name)) {
            if (!content.isEmpty()) {
                currentEntry.setField("comments", content);
            }
        } else if ("journal_ref".equals(name)) {
            journalReference = content;
        } else if ("doi".equals(name)) {
            String elementDoi = normalizeDoi(content);
            if (!elementDoi.isEmpty()) {
                doi = elementDoi;
            }
        } else if ("entry".equals(name)) {
            finishEntry();
            currentEntry = null;
        }
    }

    private void finishEntry() {
        if (isErrorEntry()) {
            if ((errorSummary != null) && !errorSummary.isEmpty()) {
                errorMessage = errorSummary;
            } else if ((errorTitle != null) && !errorTitle.isEmpty()) {
                errorMessage = errorTitle;
            } else {
                errorMessage = "Unknown arXiv API error";
            }
            return;
        }

        String arxivId = extractArxivId(entryId);
        if (arxivId.isEmpty()) {
            return;
        }

        String baseId = stripVersion(arxivId);
        currentEntry.setField("url", "https://arxiv.org/abs/" + baseId);
        currentEntry.setField("eprint", baseId);
        currentEntry.setField("eprinttype", "arxiv");
        currentEntry.setField("journal", "arXiv preprint arXiv:" + baseId);

        if (!authors.isEmpty()) {
            currentEntry.setField("author", join(authors, " and "));
        }
        if (!categories.isEmpty()) {
            currentEntry.setField("keywords", join(categories, ", "));
        }
        if ((primaryCategory != null) && !primaryCategory.isEmpty()) {
            currentEntry.setField("primaryclass", primaryCategory);
        }
        if ((journalReference != null) && !journalReference.isEmpty()) {
            currentEntry.setField("journalref", journalReference);
        }
        if ((updated != null) && !updated.isEmpty()) {
            currentEntry.setField("arxiv_updated", updated);
        }

        setPublicationDate(currentEntry, published, baseId);

        if ((doi == null) || doi.isEmpty()) {
            doi = "10.48550/arXiv." + baseId;
        }
        currentEntry.setField("doi", doi);

        entries.add(currentEntry);
    }

    private boolean isErrorEntry() {
        return ((entryId != null) && entryId.contains("/api/errors#"))
                || "Error".equalsIgnoreCase(errorTitle);
    }

    private static void setPublicationDate(BibtexEntry entry, String publishedDate, String arxivId) {
        if ((publishedDate != null) && (publishedDate.length() >= 7)
                && Character.isDigit(publishedDate.charAt(0))) {
            String year = publishedDate.substring(0, 4);
            entry.setField("year", year);
            try {
                int monthNumber = Integer.parseInt(publishedDate.substring(5, 7));
                MonthUtil.Month month = MonthUtil.getMonthByNumber(monthNumber);
                if (month.isValid()) {
                    entry.setField("month", month.bibtexFormat);
                }
            } catch (RuntimeException ignored) {
                // Keep the year even if a malformed month is returned.
            }
            return;
        }

        // Fallback for new-style arXiv identifiers: yymm.nnnnn.
        if ((arxivId != null) && arxivId.matches("\\d{4}\\..*")) {
            entry.setField("year", "20" + arxivId.substring(0, 2));
            try {
                int monthNumber = Integer.parseInt(arxivId.substring(2, 4));
                MonthUtil.Month month = MonthUtil.getMonthByNumber(monthNumber);
                if (month.isValid()) {
                    entry.setField("month", month.bibtexFormat);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String extractArxivId(String idUrl) {
        if (idUrl == null) {
            return "";
        }
        String value = idUrl.trim();
        int pos = value.indexOf("arxiv.org/abs/");
        if (pos >= 0) {
            value = value.substring(pos + "arxiv.org/abs/".length());
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        return value.trim();
    }

    private static String stripVersion(String arxivId) {
        return arxivId.replaceFirst("(?i)v\\d+$", "");
    }

    private static String normalizeDoi(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "https://doi.org/", 0, "https://doi.org/".length())) {
            value = value.substring("https://doi.org/".length());
        } else if (value.regionMatches(true, 0, "http://doi.org/", 0, "http://doi.org/".length())) {
            value = value.substring("http://doi.org/".length());
        } else if (value.regionMatches(true, 0, "http://dx.doi.org/", 0, "http://dx.doi.org/".length())) {
            value = value.substring("http://dx.doi.org/".length());
        } else if (value.regionMatches(true, 0, "doi:", 0, 4)) {
            value = value.substring(4);
        }
        return value.trim();
    }

    private static String elementName(String localName, String qualifiedName) {
        if ((localName != null) && !localName.isEmpty()) {
            return localName;
        }
        if (qualifiedName == null) {
            return "";
        }
        int colon = qualifiedName.indexOf(':');
        return colon >= 0 ? qualifiedName.substring(colon + 1) : qualifiedName;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }
}
