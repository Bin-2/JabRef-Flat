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
package net.sf.jabref.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.SearchRule;
import net.sf.jabref.export.layout.format.RemoveLatexCommands;

/**
 * Search rule for simple search.
 */
public class BasicSearch implements SearchRule {

    private final boolean caseSensitive;
    private final boolean regExp;
    private String preparedQuery;
    private boolean preparedQueryValid;
    private ArrayList<String> preparedWords;
    private Pattern[] pattern;
    private boolean[] literalPattern;
    //static RemoveBrackets removeLatexCommands = new RemoveBrackets();
    static RemoveLatexCommands removeBrackets = new RemoveLatexCommands();

    public BasicSearch(boolean caseSensitive, boolean regExp) {

        this.caseSensitive = caseSensitive;
        this.regExp = regExp;
    }

    public int applyRule(String query, BibtexEntry bibtexEntry) {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("1", query);
        return applyRule(map, bibtexEntry);
    }

    public boolean validateSearchStrings(Map<String, String> searchStrings) {
        String searchString = searchStrings.values().iterator().next();
        return prepareQuery(searchString);
    }

    public int applyRule(Map<String, String> searchStrings, BibtexEntry bibtexEntry) {
        String searchString = searchStrings.values().iterator().next();
        if ((preparedQuery == null) || !preparedQuery.equals(searchString)) {
            prepareQuery(searchString);
        }
        if (!preparedQueryValid) {
            return 0;
        }

        if (preparedWords.isEmpty()) {
            return 1;
        }

        // We need a match for all words.
        boolean[] matchFound = new boolean[preparedWords.size()];
        int remainingWords = preparedWords.size();

        for (String fieldValue : bibtexEntry.getFieldValues()) {
            if (fieldValue == null) {
                continue;
            }

            String fieldContent = normalizeFieldContent(fieldValue);

            for (int i = 0; i < preparedWords.size(); i++) {
                if (matchFound[i]) {
                    continue;
                }

                boolean matched;
                if (regExp) {
                    if (literalPattern[i]) {
                        matched = fieldContent.contains(preparedWords.get(i));
                    } else {
                        Matcher matcher = pattern[i].matcher(fieldContent);
                        matched = matcher.find();
                    }
                } else {
                    matched = fieldContent.contains(preparedWords.get(i));
                }

                if (matched) {
                    matchFound[i] = true;
                    remainingWords--;
                    if (remainingWords == 0) {
                        return 1;
                    }
                }
            }
        }

        return 0;
    }

    private String normalizeFieldContent(String fieldValue) {
        String fieldContent = fieldValue;

        // RemoveLatexCommands always creates a new buffer/string. Most fields
        // contain no LaTeX command or braces, in which case formatting would
        // return identical content. Avoid that scan and allocation.
        if ((fieldValue.indexOf('\\') >= 0)
                || (fieldValue.indexOf('{') >= 0)
                || (fieldValue.indexOf('}') >= 0)) {
            fieldContent = removeBrackets.format(fieldValue);
        }

        if (!caseSensitive) {
            fieldContent = fieldContent.toLowerCase();
        }
        return fieldContent;
    }

    private boolean prepareQuery(String query) {
        int flags = 0;
        String searchString = query;
        if (!caseSensitive) {
            searchString = searchString.toLowerCase();
            flags = Pattern.CASE_INSENSITIVE;
        }

        ArrayList<String> words = parseQuery(searchString);
        Pattern[] compiledPatterns = null;
        boolean[] literalPatterns = null;
        if (regExp) {
            try {
                compiledPatterns = new Pattern[words.size()];
                literalPatterns = new boolean[words.size()];
                for (int i = 0; i < compiledPatterns.length; i++) {
                    String word = words.get(i);
                    literalPatterns[i] = isLiteralPattern(word);
                    if (!literalPatterns[i]) {
                        compiledPatterns[i] = Pattern.compile(word, flags);
                    }
                }
            } catch (PatternSyntaxException ex) {
                preparedQuery = query;
                preparedQueryValid = false;
                preparedWords = words;
                pattern = null;
                literalPattern = null;
                return false;
            }
        }

        preparedQuery = query;
        preparedQueryValid = true;
        preparedWords = words;
        pattern = compiledPatterns;
        literalPattern = literalPatterns;
        return true;
    }

    private boolean isLiteralPattern(String word) {
        for (int i = 0; i < word.length(); i++) {
            switch (word.charAt(i)) {
                case '\\':
                case '.':
                case '^':
                case '$':
                case '|':
                case '?':
                case '*':
                case '+':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                    return false;
                default:
                    break;
            }
        }
        return true;
    }

    private ArrayList<String> parseQuery(String query) {
        StringBuilder sb = new StringBuilder();
        ArrayList<String> result = new ArrayList<>();
        int c;
        boolean escaped = false, quoted = false;
        for (int i = 0; i < query.length(); i++) {
            c = query.charAt(i);
            // Check if we are entering an escape sequence:
            if (!escaped && (c == '\\')) {
                escaped = true;
            } else {
                // See if we have reached the end of a word:
                if (!escaped && !quoted && Character.isWhitespace((char) c)) {
                    if (sb.length() > 0) {
                        result.add(sb.toString());
                        sb = new StringBuilder();
                    }
                } else if (c == '"') {
                    // Whether it is a start or end quote, store the current
                    // word if any:
                    if (sb.length() > 0) {
                        result.add(sb.toString());
                        sb = new StringBuilder();
                    }
                    quoted = !quoted;
                } else {
                    // All other possibilities exhausted, we add the char to
                    // the current word:
                    sb.append((char) c);
                }
                escaped = false;
            }
        }
        // Finished with the loop. If we have a current word, add it:
        if (sb.length() > 0) {
            result.add(sb.toString());
        }

        return result;
    }
}
