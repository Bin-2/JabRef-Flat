/*  Copyright (C) 2003-2011 JabRef contributors.
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
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collection;

import javax.swing.JPanel;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.OutputPrinter;
import net.sf.jabref.net.URLDownload;

/**
 * Fetches literature metadata from the current INSPIRE REST API.
 *
 * INSPIRE can serialize search results directly as BibTeX, which lets this
 * legacy fetcher reuse JabRef's existing BibTeX parser without HTML scraping
 * or a JSON dependency.
 */
public class INSPIREFetcher implements EntryFetcher {

    private static final String SEARCH_URL = "https://inspirehep.net/api/literature";
    private static final int MAX_RESULTS = 1000;

    // INSPIRE permits 15 requests per 5-second window. Stay slightly below
    // that ceiling when several searches are triggered in quick succession.
    private static final long MIN_REQUEST_INTERVAL_MS = 350L;
    private static long lastRequestTime;

    private volatile boolean shouldContinue;

    @Override
    public boolean processQuery(String query, ImportInspector inspector,
            OutputPrinter status) {

        if ((query == null) || query.trim().isEmpty()) {
            status.showMessage("Please enter an INSPIRE search query.");
            return false;
        }

        shouldContinue = true;
        status.setStatus("Fetching entries from INSPIRE");

        try {
            paceRequest();

            URL url = makeSearchURL(query);
            String bibtex = new URLDownload(url)
                    .setRequestProperty("Accept", "application/x-bibtex")
                    .downloadToString("UTF-8");

            if (!shouldContinue) {
                return false;
            }

            Collection<BibtexEntry> entries = BibtexParser.fromString(bibtex);
            if (entries == null) {
                status.showMessage("Could not parse the response from INSPIRE.");
                return false;
            }

            status.setStatus("Adding fetched entries");
            addEntries(entries, inspector);
            return true;

        } catch (IOException e) {
            status.showMessage("INSPIRE search failed: " + safeMessage(e));
            return false;
        }
    }

    private void addEntries(Collection<BibtexEntry> entries, ImportInspector inspector) {
        int total = entries.size();
        int current = 0;

        for (BibtexEntry entry : entries) {
            if (!shouldContinue) {
                break;
            }

            inspector.addEntry(entry);
            current++;
            inspector.setProgress(current, total);
        }
    }

    private URL makeSearchURL(String query) throws IOException {
        StringBuilder address = new StringBuilder(SEARCH_URL);
        address.append("?q=").append(urlEncode(query.trim()));
        address.append("&size=").append(MAX_RESULTS);
        address.append("&page=1&format=bibtex");
        return new URL(address.toString());
    }

    private static synchronized void paceRequest() throws IOException {
        long now = System.currentTimeMillis();
        long wait = MIN_REQUEST_INTERVAL_MS - (now - lastRequestTime);
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to contact INSPIRE", e);
            }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static String urlEncode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IOException("UTF-8 is not available", e);
        }
    }

    private static String safeMessage(IOException exception) {
        String message = exception.getMessage();
        return ((message == null) || message.trim().isEmpty())
                ? exception.getClass().getSimpleName()
                : message;
    }

    @Override
    public void stopFetching() {
        shouldContinue = false;
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
        return "INSPIRE";
    }

    @Override
    public JPanel getOptionsPanel() {
        return null;
    }

    @Override
    public String getTitle() {
        return Globals.menuTitle(getKeyName());
    }
}
