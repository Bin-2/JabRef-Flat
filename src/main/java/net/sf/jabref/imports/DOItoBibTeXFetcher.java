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

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import java.text.SimpleDateFormat;
import java.util.Date;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.OutputPrinter;
import net.sf.jabref.Util;

public class DOItoBibTeXFetcher implements EntryFetcher {

    private static final String URL_PATTERN = "https://dx.doi.org/%s";
    final CaseKeeper caseKeeper = new CaseKeeper();
    final UnitFormatter unitFormatter = new UnitFormatter();

    @Override
    public void stopFetching() {
        // nothing needed as the fetching is a single HTTP GET
    }

    @Override
    public boolean processQuery(String query, ImportInspector inspector, OutputPrinter status) {

        BibtexEntry entry = getEntryFromDOI(query, status);
        if (entry != null) {
            inspector.addEntry(entry);
            return true;
        } else {
            return false;
        }

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
        // no special icon for this fetcher available.
        // Therefore, we return some kind of default icon
        return GUIGlobals.getIconUrl("www");
    }

    @Override
    public String getHelpPage() {
        return "DOItoBibTeXHelp.html";
    }

    @Override
    public JPanel getOptionsPanel() {
        // no additional options available
        return null;
    }

    public BibtexEntry getEntryFromDOI(String doi, OutputPrinter status) {
        String urlString = String.format(URL_PATTERN, encodeDoiPath(doi));

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/x-bibtex; charset=utf-8");
            conn.setRequestProperty("User-Agent", "DOItoBibTeXFetcher/2.0 (+https://your-domain.example)");

            int code = conn.getResponseCode();
            if (code == 200) {
                String bibtexString = Util.getResultsWithEncoding(conn, "UTF-8");

                // Normalize common typographical dashes in page ranges to LaTeX "--"
                bibtexString = bibtexString.replaceAll("(pages=\\{[0-9]+)\\p{Pd}([0-9]+\\})", "$1--$2");

                BibtexEntry entry = BibtexParser.singleFromString(bibtexString);
                if (entry != null) {
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
                }

                // === New timestamp field ===
                // Format: yyyy.MM.dd HH:mm:ss
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
                String timestamp = fmt.format(new Date());
                entry.setField("timestamp", timestamp);

                return entry;
            } else if (code == 404 || code == 410) {
                if (status != null) {
                    status.showMessage(Globals.lang("Unknown DOI: '%0'.", doi),
                            Globals.lang("Get BibTeX entry from DOI"),
                            JOptionPane.INFORMATION_MESSAGE);
                }
                return null;
            } else if (code == 406) {
                if (status != null) {
                    status.showMessage(Globals.lang("Server does not provide BibTeX for this DOI (HTTP 406)."),
                            Globals.lang("Get BibTeX entry from DOI"),
                            JOptionPane.WARNING_MESSAGE);
                }
                return null;
            } else if (code == 429) {
                if (status != null) {
                    status.showMessage(Globals.lang("Rate limited by provider (HTTP 429). Try later."),
                            Globals.lang("Get BibTeX entry from DOI"),
                            JOptionPane.WARNING_MESSAGE);
                }
                return null;
            } else {
                if (status != null) {
                    status.showMessage(Globals.lang("DOI request failed: HTTP %0", String.valueOf(code)),
                            Globals.lang("Get BibTeX entry from DOI"),
                            JOptionPane.ERROR_MESSAGE);
                }
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String encodeDoiPath(String raw) {
        String doi = raw.trim().replaceFirst("^(?i)doi:\\s*", "");
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
        // RFC 3986 unreserved: ALPHA / DIGIT / "-" / "." / "_" / "~"
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')
                || cp == '-' || cp == '.' || cp == '_' || cp == '~';
    }

}
