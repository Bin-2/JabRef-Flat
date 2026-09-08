/*  Copyright (C) 2014 JabRef contributors.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.jabref.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.OutputPrinter;
import net.sf.jabref.net.URLDownload;

/**
 * Fetches one bibliography entry from a DOI using DOI content negotiation.
 */
public class DOItoBibTeXFetcher implements EntryFetcher {

    private static final String DOI_RESOLVER = "https://doi.org/";

    final CaseKeeper caseKeeper = new CaseKeeper();
    final UnitFormatter unitFormatter = new UnitFormatter();

    @Override
    public void stopFetching() {
        // Nothing needed: a DOI lookup is a single HTTP GET.
    }

    @Override
    public boolean processQuery(String query, ImportInspector inspector, OutputPrinter status) {
        BibtexEntry entry = getEntryFromDOI(query, status);
        if (entry == null) {
            return false;
        }
        inspector.addEntry(entry);
        return true;
    }

    @Override
    public String getTitle() {
        return "DOI to BibTeX";
    }

    @Override
    public String getKeyName() {
        return "DOItoBibTeX";
    }

    @Override
    public URL getIcon() {
        return GUIGlobals.getIconUrl("www");
    }

    @Override
    public String getHelpPage() {
        return "DOItoBibTeXHelp.html";
    }

    @Override
    public JPanel getOptionsPanel() {
        return null;
    }

    public BibtexEntry getEntryFromDOI(String rawDoi, OutputPrinter status) {
        String doi = normalizeDoi(rawDoi);
        if (doi.isEmpty()) {
            showMessage(status,
                    Globals.lang("Invalid DOI: '%0'.", rawDoi == null ? "" : rawDoi),
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }

        try {
            URL url = new URL(DOI_RESOLVER + encodeDoiPath(doi));
            String bibtexString = new URLDownload(url)
                    .setRequestProperty("Accept", "application/x-bibtex")
                    .downloadToString("UTF-8");

            if ((bibtexString == null) || bibtexString.trim().isEmpty()) {
                showMessage(status,
                        Globals.lang("Server returned no BibTeX data for DOI '%0'.", doi),
                        JOptionPane.WARNING_MESSAGE);
                return null;
            }

            // Normalize common typographical dashes in page ranges to LaTeX "--".
            bibtexString = bibtexString.replaceAll("(pages=\\{[0-9]+)\\p{Pd}([0-9]+\\})", "$1--$2");

            BibtexEntry entry = BibtexParser.singleFromString(bibtexString);
            if (entry == null) {
                showMessage(status,
                        Globals.lang("Could not parse BibTeX data returned for DOI '%0'.", doi),
                        JOptionPane.WARNING_MESSAGE);
                return null;
            }

            String title = entry.getField("title");
            if (title != null) {
                if (Globals.prefs.getBoolean("useUnitFormatterOnSearch")) {
                    title = unitFormatter.format(title);
                }
                if (Globals.prefs.getBoolean("useCaseKeeperOnSearch")) {
                    title = caseKeeper.format(title);
                }
                entry.setField("title", title);
            }

            // Do not set JabRef's local timestamp here. Automatic fields are
            // applied when the entry is actually imported into the database.
            return entry;
        } catch (IOException e) {
            handleDownloadError(doi, status, e);
            return null;
        } catch (RuntimeException e) {
            e.printStackTrace();
            showMessage(status,
                    Globals.lang("Could not retrieve BibTeX data for DOI '%0'.", doi),
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /**
     * Accepts a bare DOI, doi: prefix, or doi.org/dx.doi.org URL and returns
     * the DOI name only.
     */
    static String normalizeDoi(String raw) {
        if (raw == null) {
            return "";
        }

        String doi = raw.trim();
        doi = doi.replaceFirst("(?i)^doi:\\s*", "");
        doi = doi.replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "");
        doi = doi.replaceFirst("(?i)^https?://www\\.doi\\.org/", "");

        // DOI URLs occasionally arrive with percent-encoded path characters.
        doi = percentDecode(doi).trim();
        return doi;
    }

    private void handleDownloadError(String doi, OutputPrinter status, IOException error) {
        String message = error.getMessage();
        if (message == null) {
            message = "";
        }

        if (message.startsWith("HTTP 404") || message.startsWith("HTTP 410")) {
            showMessage(status, Globals.lang("Unknown DOI: '%0'.", doi), JOptionPane.INFORMATION_MESSAGE);
        } else if (message.startsWith("HTTP 406")) {
            showMessage(status,
                    Globals.lang("Server does not provide BibTeX for this DOI (HTTP 406)."),
                    JOptionPane.WARNING_MESSAGE);
        } else if (message.startsWith("HTTP 429")) {
            showMessage(status,
                    Globals.lang("Rate limited by provider (HTTP 429). Try later."),
                    JOptionPane.WARNING_MESSAGE);
        } else {
            error.printStackTrace();
            showMessage(status,
                    Globals.lang("Could not retrieve BibTeX data for DOI '%0': %1", doi, message),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void showMessage(OutputPrinter status, String message, int messageType) {
        if (status != null) {
            status.showMessage(message,
                    Globals.lang("Get BibTeX entry from DOI"),
                    messageType);
        }
    }

    private static String encodeDoiPath(String doi) {
        String[] parts = doi.split("/", -1);
        StringBuilder out = new StringBuilder(doi.length() + 8);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encodePathSegment(parts[i]));
        }
        return out.toString();
    }

    private static String encodePathSegment(String segment) {
        StringBuilder sb = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length();) {
            int cp = segment.codePointAt(i);
            if (isUnreserved(cp)) {
                sb.appendCodePoint(cp);
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%');
                    String hex = Integer.toHexString(b & 0xFF).toUpperCase(Locale.ROOT);
                    if (hex.length() == 1) {
                        sb.append('0');
                    }
                    sb.append(hex);
                }
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static boolean isUnreserved(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')
                || cp == '-' || cp == '.' || cp == '_' || cp == '~';
    }

    /** Percent-decodes without treating '+' as a space. */
    private static String percentDecode(String value) {
        StringBuilder result = new StringBuilder(value.length());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        for (int i = 0; i < value.length();) {
            if ((value.charAt(i) == '%') && (i + 2 < value.length())) {
                encoded.reset();
                int start = i;
                while ((i + 2 < value.length()) && value.charAt(i) == '%') {
                    int high = Character.digit(value.charAt(i + 1), 16);
                    int low = Character.digit(value.charAt(i + 2), 16);
                    if ((high < 0) || (low < 0)) {
                        break;
                    }
                    encoded.write((high << 4) + low);
                    i += 3;
                }
                if (encoded.size() > 0) {
                    result.append(new String(encoded.toByteArray(), StandardCharsets.UTF_8));
                    continue;
                }
                i = start;
            }
            result.append(value.charAt(i));
            i++;
        }
        return result.toString();
    }
}
