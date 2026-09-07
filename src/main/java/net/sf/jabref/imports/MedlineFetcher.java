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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.OutputPrinter;

/**
 * Fetch or search from Pubmed http://www.ncbi.nlm.nih.gov/sites/entrez/
 *
 */
public class MedlineFetcher implements EntryFetcher {

    protected class SearchResult {

        public int count = 0;

        public int retmax = 0;

        public int retstart = 0;

        public String ids = "";

        public void addID(String id) {
            if (ids.equals("")) {
                ids = id;
            } else {
                ids += "," + id;
            }
        }
    }

    /** Ask before importing more than this many references. */
    public static final int PROMPT_THRESHOLD = 20;

    /** Number of PubMed records requested per ESearch/EFetch batch. */
    public static final int FETCH_BATCH_SIZE = 100;

    boolean shouldContinue;

    OutputPrinter frame;

    ImportInspector dialog;

    public String toSearchTerm(String in) {
        // Preserve the old comma-as-AND behavior, but let URLEncoder handle
        // whitespace and all other characters safely.
        return in.trim().replaceAll("\\s*,\\s*", " AND ");
    }

    /**
     * Gets the initial list of ids
     */
    public SearchResult getIds(String term, int start, int pacing) throws IOException {
        String xml = NcbiEutils.esearch(term, start, pacing);
        return parseSearchResult(xml);
    }

    private SearchResult parseSearchResult(String xml) throws IOException {
        final SearchResult result = new SearchResult();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setValidating(false);
            factory.setNamespaceAware(false);
            SAXParser parser = factory.newSAXParser();
            parser.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")), new DefaultHandler() {
                private final StringBuilder text = new StringBuilder();

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    text.setLength(0);
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    text.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    String value = text.toString().trim();
                    if ("Id".equals(qName)) {
                        result.addID(value);
                    } else if ("Count".equals(qName)) {
                        result.count = parseInteger(value);
                    } else if ("RetMax".equals(qName)) {
                        result.retmax = parseInteger(value);
                    } else if ("RetStart".equals(qName)) {
                        result.retstart = parseInteger(value);
                    }
                    text.setLength(0);
                }

                private int parseInteger(String value) {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            });
        } catch (ParserConfigurationException e) {
            throw new IOException("Could not configure PubMed response parser", e);
        } catch (SAXException e) {
            throw new IOException("Could not parse PubMed search response", e);
        }
        return result;
    }

    public void stopFetching() {
        shouldContinue = false;
    }

    public String getHelpPage() {
        return GUIGlobals.medlineHelp;
    }

    public URL getIcon() {
        return GUIGlobals.getIconUrl("www");
    }

    public String getKeyName() {
        return "Medline";
    }

    public JPanel getOptionsPanel() {
        // No Option Panel
        return null;
    }

    public String getTitle() {
        return "Medline";
    }

    public boolean processQuery(String query, ImportInspector dialog, OutputPrinter frame) {

        shouldContinue = true;

        query = query.trim().replace(';', ',');

        if (query.matches("\\d+(\\s*,\\s*\\d+)*")) {
            frame.setStatus(Globals.lang("Fetching Medline by id..."));

            List<BibtexEntry> bibs;
            try {
                bibs = MedlineImporter.fetchMedlineChecked(query.replaceAll("\\s+", ""), frame);
            } catch (IOException e) {
                frame.showMessage(Globals.lang("Could not connect to PubMed") + ": " + e.getMessage());
                return false;
            }

            if (bibs.size() == 0) {
                frame.showMessage(Globals.lang("No references found"));
            }

            for (BibtexEntry entry : bibs) {
                dialog.addEntry(entry);
            }
            return true;
        }

        if (query.length() > 0) {
            frame.setStatus(Globals.lang("Fetching Medline by term..."));

            String searchTerm = toSearchTerm(query);

            // get the ids from entrez
            SearchResult result;
            try {
                result = getIds(searchTerm, 0, 1);
            } catch (IOException e) {
                frame.showMessage(Globals.lang("Could not connect to PubMed") + ": " + e.getMessage());
                return false;
            }

            if (result.count == 0) {
                frame.showMessage(Globals.lang("No references found"));
                return false;
            }

            int numberToFetch = result.count;
            if (numberToFetch > PROMPT_THRESHOLD) {

                while (true) {
                    String strCount = JOptionPane.showInputDialog(Globals.lang("References found")
                            + ": " + numberToFetch + "  "
                            + Globals.lang("Number of references to fetch?"), Integer
                            .toString(numberToFetch));

                    if (strCount == null) {
                        frame.setStatus(Globals.lang("Medline import canceled"));
                        return false;
                    }

                    try {
                        numberToFetch = Integer.parseInt(strCount.trim());
                        break;
                    } catch (RuntimeException ex) {
                        frame.showMessage(Globals.lang("Please enter a valid number"));
                    }
                }
            }

            for (int i = 0; i < numberToFetch; i += FETCH_BATCH_SIZE) {
                if (!shouldContinue) {
                    break;
                }

                int noToFetch = Math.min(FETCH_BATCH_SIZE, numberToFetch - i);

                // get the ids from entrez
                try {
                    result = getIds(searchTerm, i, noToFetch);
                    List<BibtexEntry> bibs = MedlineImporter.fetchMedlineChecked(result.ids, frame);
                    for (BibtexEntry entry : bibs) {
                        dialog.addEntry(entry);
                    }
                } catch (IOException e) {
                    frame.showMessage(Globals.lang("Could not connect to PubMed") + ": " + e.getMessage());
                    return false;
                }
                dialog.setProgress(i + noToFetch, numberToFetch);
            }
            return true;
        }
        frame.showMessage(
                Globals.lang("Please enter a comma separated list of Medline IDs (numbers) or search terms."),
                Globals.lang("Input error"), JOptionPane.ERROR_MESSAGE);
        return false;
    }
}
