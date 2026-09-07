/*  Copyright (C) 2011 Sascha Hunold.
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
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JPanel;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.DuplicateCheck;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.OutputPrinter;
import net.sf.jabref.net.URLDownload;

/**
 * Fetches publication metadata from the DBLP publication search API.
 *
 * DBLP can return search results directly as BibTeX. Using that format keeps
 * this legacy fetcher small and avoids the old multi-step HTML scraping path.
 */
public class DBLPFetcher implements EntryFetcher {

    private static final String SEARCH_URL = "https://dblp.org/search/publ/api";
    private static final int MAX_RESULTS = 1000;
    private static final String DBLP_UPDATED_FIELD = "dblp_updated";

    private volatile boolean shouldContinue = false;

    @Override
    public void stopFetching() {
        shouldContinue = false;
    }

    @Override
    public boolean processQuery(String query, ImportInspector inspector,
            OutputPrinter status) {

        if ((query == null) || query.trim().isEmpty()) {
            status.showMessage("Please enter a DBLP search query.");
            return false;
        }

        shouldContinue = true;

        try {
            URL url = makeSearchURL(query);
            String bibtex = new URLDownload(url)
                    .setRequestProperty("Accept", "application/x-bibtex")
                    .downloadToString();

            if (!shouldContinue) {
                return false;
            }

            Collection<BibtexEntry> entries = BibtexParser.fromString(bibtex);
            if (entries == null) {
                status.showMessage("Could not parse the response from DBLP.");
                return false;
            }

            addEntries(entries, inspector);
            return true;

        } catch (IOException e) {
            status.showMessage("DBLP search failed: " + safeMessage(e));
            return false;
        }
    }

    private void addEntries(Collection<BibtexEntry> entries, ImportInspector inspector) {
        Set<String> knownKeys = new HashSet<String>();
        int total = entries.size();
        int current = 0;

        // Keep the historical DBLP behavior: results in the inspection dialog
        // are not marked as duplicates solely by JabRef's similarity heuristic.
        double savedThreshold = DuplicateCheck.duplicateThreshold;
        DuplicateCheck.duplicateThreshold = Double.MAX_VALUE;
        try {
            for (BibtexEntry entry : entries) {
                if (!shouldContinue) {
                    break;
                }

                normalizeDblpMetadata(entry);

                String citeKey = entry.getCiteKey();
                if ((citeKey == null) || knownKeys.add(citeKey)) {
                    inspector.addEntry(entry);
                }

                current++;
                inspector.setProgress(current, total);
            }
        } finally {
            DuplicateCheck.duplicateThreshold = savedThreshold;
        }
    }

    /**
     * DBLP exports its record metadata update time in the field named
     * "timestamp". JabRef uses that field for the local time an entry was
     * added to the bibliography, so keep the DBLP value under a provider-
     * specific field and leave "timestamp" available for JabRef.
     */
    private void normalizeDblpMetadata(BibtexEntry entry) {
        String dblpTimestamp = entry.getField("timestamp");
        if ((dblpTimestamp == null) || dblpTimestamp.trim().isEmpty()) {
            return;
        }

        entry.setField(DBLP_UPDATED_FIELD, dblpTimestamp);
        entry.clearField("timestamp");
    }

    private URL makeSearchURL(String query) throws IOException {
        StringBuilder address = new StringBuilder(SEARCH_URL);
        address.append("?q=").append(URLEncoder.encode(query.trim(), "UTF-8"));
        address.append("&h=").append(MAX_RESULTS);
        address.append("&f=0&c=0&format=bib");
        return new URL(address.toString());
    }

    private static String safeMessage(IOException exception) {
        String message = exception.getMessage();
        return ((message == null) || message.trim().isEmpty())
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Override
    public String getTitle() {
        return "DBLP";
    }

    @Override
    public String getKeyName() {
        return "DBLP";
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
