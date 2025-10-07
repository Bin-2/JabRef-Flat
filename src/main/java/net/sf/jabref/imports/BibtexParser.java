/*
 Copyright (C) 2003-06 David Weitzman, Nizar N. Batada, Morten O. Alver, Christopher Oezbek
 Copyright (C) 2015 Oliver Kopp

 All programs in this directory and
 subdirectories are published under the GNU General Public License as
 described below.

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or (at
 your option) any later version.

 This program is distributed in the hope that it will be useful, but
 WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 USA

 Further information about the GNU GPL is available at:
 http://www.gnu.org/copyleft/gpl.ja.html

 */
package net.sf.jabref.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.jabref.*;
import net.sf.jabref.collab.FileUpdateMonitor;

import java.util.concurrent.TimeUnit;

/**
 * Class for importing BibTeX-files.
 *
 * Use:
 *
 * BibtexParser parser = new BibtexParser(reader);
 *
 * ParserResult result = parser.parse();
 *
 * or
 *
 * ParserResult result = BibtexParser.parse(reader);
 *
 * Can be used stand-alone.
 *
 * @author David Weitzman
 * @author Nizar N. Batada
 * @author Morten O. Alver
 * @author Christopher Oezbek
 */
public class BibtexParser {

    // Add these instance variables for timing
    private long _startTime;
    private static final int NUM_PHASES = 12;
    private long[] _phaseTimes = new long[NUM_PHASES];
    private String[] _phaseNames = {
        "Total", "Whitespace", "EntryDetection", "EntryParsing",
        "FieldParsing", "StringOps", "DatabaseOps", "FileReading",
        "BracketParsing", "TextToken", "FieldContent", "Other"
    };

    // Constants for phase indices
    private static final int PHASE_TOTAL = 0;
    private static final int PHASE_WHITESPACE = 1;
    private static final int PHASE_ENTRY_DETECTION = 2;
    private static final int PHASE_ENTRY_PARSING = 3;
    private static final int PHASE_FIELD_PARSING = 4;
    private static final int PHASE_STRING_OPS = 5;
    private static final int PHASE_DATABASE_OPS = 6;
    private static final int PHASE_FILE_READING = 7;
    private static final int PHASE_BRACKET_PARSING = 8;
    private static final int PHASE_TEXT_TOKEN = 9;
    private static final int PHASE_FIELD_CONTENT = 10;
    private static final int PHASE_OTHER = 11;

    // Add buffering for I/O optimization
    private BufferedReader _bufferedReader;
    private PushbackReader _in;

    // Add larger buffer for bulk reading
    private char[] _largeBuffer = new char[65536]; // 64KB buffer
    private int _largeBufferPos = 0;
    private int _largeBufferLength = 0;
    private boolean _useLargeBuffer = true;

    // Add this method for timing
    private void startTimer() {
        _startTime = System.nanoTime();
    }

    private void recordTime(int phase) {
        if (phase < 0 || phase >= NUM_PHASES) {
            phase = PHASE_OTHER;
        }
        long currentTime = System.nanoTime();
        long elapsed = currentTime - _startTime;
        _phaseTimes[phase] += elapsed;
        _startTime = currentTime;
    }

    private void printTimingStats() {
        System.out.println("=== BibTeX Parser Performance Profile ===");
        for (int i = 0; i < NUM_PHASES; i++) {
            double seconds = _phaseTimes[i] / 1_000_000_000.0;
            if (seconds > 0.001) { // Only show phases that took significant time
                System.out.printf("%-15s: %.3f seconds%n", _phaseNames[i], seconds);
            }
        }

        // Calculate and print percentage breakdown
        // double totalSeconds = _phaseTimes[PHASE_TOTAL] / 1_000_000_000.0;
        // Calculate total by summing all phases (excluding PHASE_TOTAL itself)
        long totalNanos = 0;
        for (int i = 1; i < NUM_PHASES; i++) {
            totalNanos += _phaseTimes[i];
        }
        double totalSeconds = totalNanos / 1_000_000_000.0;

        System.out.printf("%nTotal parsing time: %.3f seconds%n", totalSeconds);
        if (totalSeconds > 0) {
            System.out.println("Percentage breakdown:");
            for (int i = 1; i < NUM_PHASES; i++) {
                double percent = (_phaseTimes[i] * 100.0) / _phaseTimes[PHASE_TOTAL];
                if (percent > 1.0) { // Only show phases > 1%
                    System.out.printf("%-15s: %.1f%%%n", _phaseNames[i], percent);
                }
            }
        }
    }
    //********************************

    private static final Logger logger = Logger.getLogger(BibtexParser.class.getName());

    private BibtexDatabase _db;

    private HashMap<String, BibtexEntryType> entryTypes;

    private boolean _eof = false;

    private int line = 1;

    private FieldContentParser fieldContentParser = new FieldContentParser();

    private ParserResult _pr;

    private static final Integer LOOKAHEAD = 65536;

    private final boolean autoDoubleBraces;

    public BibtexParser(Reader in) {

        if (in == null) {
            throw new NullPointerException();
        }
        if (Globals.prefs == null) {
            Globals.prefs = JabRefPreferences.getInstance();
        }
        autoDoubleBraces = Globals.prefs.getBoolean("autoDoubleBraces");
        _in = new PushbackReader(in, LOOKAHEAD);
    }

    /**
     * Shortcut usage to create a Parser and read the input.
     *
     * @param in - Reader to read from
     * @throws IOException
     */
    public static ParserResult parse(Reader in) throws IOException {
        BibtexParser parser = new BibtexParser(in);
        return parser.parse();
    }

    /**
     * Parses BibtexEntries from the given string and returns the collection of
     * all entries found.
     *
     * @param bibtexString
     *
     * @return Returns null if an error occurred, returns an empty collection if
     * no entries where found.
     */
    public static Collection<BibtexEntry> fromString(String bibtexString) {
        StringReader reader = new StringReader(bibtexString);
        BibtexParser parser = new BibtexParser(reader);
        try {
            return parser.parse().getDatabase().getEntries();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses BibtexEntries from the given string and returns one entry found
     * (or null if none found)
     *
     * It is undetermined which entry is returned, so use this in case you know
     * there is only one entry in the string.
     *
     * @param bibtexString
     *
     * @return The bibtexentry or null if non was found or an error occurred.
     */
    public static BibtexEntry singleFromString(String bibtexString) {
        Collection<BibtexEntry> c = fromString(bibtexString);
        if ((c == null) || (c.size() == 0)) {
            return null;
        }
        return c.iterator().next();
    }

    /**
     * Check whether the source is in the correct format for this importer.
     */
    public static boolean isRecognizedFormat(Reader inOrig) throws IOException {
        // Our strategy is to look for the "@<type>    {" line.
        BufferedReader in = new BufferedReader(inOrig);

        Pattern pat1 = Pattern.compile("@[a-zA-Z]*\\s*\\{");

        String str;

        while ((str = in.readLine()) != null) {

            if (pat1.matcher(str).find()) {
                return true;
            } else if (str.startsWith(GUIGlobals.SIGNATURE)) {
                return true;
            }
        }

        return false;
    }

    private void skipWhitespace() throws IOException {
        long start = System.nanoTime();
        try {
            int c;
            while (true) {
                c = read();
                if ((c == -1) || (c == 65535)) {
                    _eof = true;
                    return;
                }

                if (Character.isWhitespace((char) c)) {
                    continue;
                } else {
                    unread(c);
                    break;
                }
            }
        } finally {
            _phaseTimes[PHASE_WHITESPACE] += System.nanoTime() - start;
        }
    }

    private String skipAndRecordWhitespace(int j) throws IOException {
        int c;
        StringBuilder sb = new StringBuilder();
        if (j != ' ') {
            sb.append((char) j);
        }
        while (true) {
            c = read();
            if ((c == -1) || (c == 65535)) {
                _eof = true;
                return sb.toString();
            }

            if (Character.isWhitespace((char) c)) {
                if (c != ' ') {
                    sb.append((char) c);
                }
                continue;
            } else // found non-whitespace char
            // Util.pr("SkipWhitespace, stops: "+c);
            {
                unread(c);
            }
            /*
			 * try { Thread.currentThread().sleep(500); } catch
			 * (InterruptedException ex) {}
             */
            break;
        }
        return sb.toString();
    }

    /**
     * Will parse the BibTex-Data found when reading from reader.
     *
     * The reader will be consumed.
     *
     * Multiple calls to parse() return the same results
     *
     * @return ParserResult
     * @throws IOException
     */
    public ParserResult parse() throws IOException {
        // Initialize timing arrays
        for (int i = 0; i < NUM_PHASES; i++) {
            _phaseTimes[i] = 0;
        }
        startTimer();

        // If we already parsed this, just return it.
        if (_pr != null) {
            return _pr;
        }

        _db = new BibtexDatabase(); // Bibtex related contents.
        HashMap<String, String> meta = new HashMap<String, String>();
        entryTypes = new HashMap<String, BibtexEntryType>(); // To store custem entry types parsed.
        _pr = new ParserResult(_db, null, entryTypes);

        // First see if we can find the version number of the JabRef version that
        // wrote the file:
        String versionNum = readJabRefVersionNumber();
        if (versionNum != null) {
            _pr.setJabrefVersion(versionNum);
            setMajorMinorVersions();
        }

        skipWhitespace();
        recordTime(PHASE_WHITESPACE);

        try {
            while (!_eof) {
                boolean found = consumeUncritically('@');
                recordTime(PHASE_ENTRY_DETECTION);

                if (!found) {
                    break;
                }
                skipWhitespace();
                recordTime(PHASE_WHITESPACE);

                String entryType = parseTextToken();
                recordTime(PHASE_TEXT_TOKEN);

                BibtexEntryType tp = BibtexEntryType.getType(entryType);
                boolean isEntry = (tp != null);
                // Util.pr(tp.getName());
                if (!isEntry) {
                    // The entry type name was not recognized. This can mean
                    // that it is a string, preamble, or comment. If so,
                    // parse and set accordingly. If not, assume it is an entry
                    // with an unknown type.
                    if (entryType.toLowerCase().equals("preamble")) {
                        _db.setPreamble(parsePreamble());
                    } else if (entryType.toLowerCase().equals("string")) {
                        BibtexString bs = parseString();
                        try {
                            _db.addString(bs);
                        } catch (KeyCollisionException ex) {
                            _pr.addWarning(Globals.lang("Duplicate string name") + ": "
                                    + bs.getName());
                            // ex.printStackTrace();
                        }
                    } else if (entryType.toLowerCase().equals("comment")) {
                        StringBuffer commentBuf = parseBracketedTextExactly();
                        /**
                         *
                         * Metadata are used to store Bibkeeper-specific
                         * information in .bib files.
                         *
                         * Metadata are stored in bibtex files in the format
                         *
                         * @comment{jabref-meta: type:data0;data1;data2;...}
                         *
                         * Each comment that starts with the META_FLAG is stored
                         * in the meta HashMap, with type as key. Unluckily, the
                         * old META_FLAG bibkeeper-meta: was used in JabRef 1.0
                         * and 1.1, so we need to support it as well. At least
                         * for a while. We'll always save with the new one.
                         */
                        String comment = commentBuf.toString().replaceAll("[\\x0d\\x0a]", "");
                        if (comment.substring(0,
                                Math.min(comment.length(), GUIGlobals.META_FLAG.length())).equals(
                                GUIGlobals.META_FLAG)
                                || comment.substring(0,
                                        Math.min(comment.length(), GUIGlobals.META_FLAG_OLD.length()))
                                        .equals(GUIGlobals.META_FLAG_OLD)) {

                            String rest;
                            if (comment.substring(0, GUIGlobals.META_FLAG.length()).equals(
                                    GUIGlobals.META_FLAG)) {
                                rest = comment.substring(GUIGlobals.META_FLAG.length());
                            } else {
                                rest = comment.substring(GUIGlobals.META_FLAG_OLD.length());
                            }

                            int pos = rest.indexOf(':');

                            if (pos > 0) {
                                meta.put(rest.substring(0, pos), rest.substring(pos + 1));
                            }
                            // We remove all line breaks in the metadata - these
                            // will have been inserted
                            // to prevent too long lines when the file was
                            // saved, and are not part of the data.

                        } else if (comment.substring(0,
                                Math.min(comment.length(), GUIGlobals.ENTRYTYPE_FLAG.length())).equals(
                                GUIGlobals.ENTRYTYPE_FLAG)) {
                            // A custom entry type can also be stored in a
                            // "@comment"
                            CustomEntryType typ = CustomEntryType.parseEntryType(comment);
                            entryTypes.put(typ.getName().toLowerCase(), typ);
                        } else {
                            // FIXME: user comments are simply dropped
                            // at least, we log that we ignored the comment
                            Globals.logger(Globals.lang("Dropped comment from database") + ":" + comment);
                        }
                    } else {
                        // The entry type was not recognized. This may mean that
                        // it is a custom entry type whose definition will
                        // appear
                        // at the bottom of the file. So we use an
                        // UnknownEntryType
                        // to remember the type name by.
                        tp = new UnknownEntryType(entryType.toLowerCase());
                        // System.out.println("unknown type: "+entryType);
                        isEntry = true;
                    }
                    recordTime(PHASE_OTHER);
                }

                if (isEntry) // True if not comment, preamble or string.
                {
                    /**
                     * Morten Alver 13 Aug 2006: Trying to make the parser more
                     * robust. If an exception is thrown when parsing an entry,
                     * drop the entry and try to resume parsing. Add a warning
                     * for the user.
                     *
                     * An alternative solution is to try rescuing the entry for
                     * which parsing failed, by returning the entry with the
                     * exception and adding it before parsing is continued.
                     */
                    try {
                        startTimer(); // Reset for entry parsing
                        BibtexEntry be = parseEntry(tp);
                        recordTime(PHASE_ENTRY_PARSING);

                        // Time database operations separately
                        long dbStart = System.nanoTime();
                        boolean duplicateKey = _db.insertEntry(be);
                        _phaseTimes[PHASE_DATABASE_OPS] += System.nanoTime() - dbStart;

                        if (duplicateKey) // JZTODO lyrics
                        {
                            _pr.addDuplicateKey(be.getCiteKey());
                        } // _pr.addWarning(Globals.lang("duplicate BibTeX key") + ": "
                        //	+ be.getCiteKey() + " ("
                        //	+ Globals.lang("grouping may not work for this entry") + ")");                        
                        else if (be.getCiteKey() == null || be.getCiteKey().equals("")) {
                            _pr.addWarning(Globals.lang("empty BibTeX key") + ": "
                                    + be.getAuthorTitleYear(40) + " ("
                                    + Globals.lang("grouping may not work for this entry") + ")");
                        }
                    } catch (IOException ex) {
                        logger.log(Level.WARNING, ex.getMessage(), ex);
                        _pr.addWarning(Globals.lang("Error occured when parsing entry") + ": '"
                                + ex.getMessage() + "'. " + Globals.lang("Skipped entry."));
                    }
                }
                skipWhitespace();
                recordTime(PHASE_WHITESPACE);
            }
            // Before returning the database, update entries with unknown type
            // based on parsed type definitions, if possible.
            checkEntryTypes(_pr);

            // Instantiate meta data:
            _pr.setMetaData(new MetaData(meta, _db));

            recordTime(PHASE_TOTAL); // Final total time
            // printTimingStats();

            return _pr;
        } catch (KeyCollisionException kce) {
            // kce.printStackTrace();
            throw new IOException("Duplicate ID in bibtex file: " + kce.toString());
        }
    }

    private int peek() throws IOException {
        int c = read();
        unread(c);

        return c;
    }

    private int read() throws IOException {
        long start = System.nanoTime();
        try {
            if (_useLargeBuffer && _largeBufferPos < _largeBufferLength) {
                char c = _largeBuffer[_largeBufferPos++];
                if (c == '\n') {
                    line++;
                }
                return c;
            }

            // Fall back to original reading if large buffer is exhausted
            _useLargeBuffer = false;
            int c = _in.read();
            if (c == '\n') {
                line++;
            }
            return c;
        } finally {
            _phaseTimes[PHASE_FILE_READING] += System.nanoTime() - start;
        }
    }

    private void unread(int c) throws IOException {
        long start = System.nanoTime();
        try {
            if (_largeBufferPos > 0) {
                _largeBufferPos--;
                if (c == '\n') {
                    line--;
                }
            } else {
                _in.unread(c);
            }
        } finally {
            _phaseTimes[PHASE_FILE_READING] += System.nanoTime() - start;
        }
    }

    private void fillLargeBuffer() throws IOException {
        long start = System.nanoTime();
        try {
            _largeBufferLength = _bufferedReader.read(_largeBuffer);
            _largeBufferPos = 0;
            _useLargeBuffer = (_largeBufferLength > 0);
        } finally {
            _phaseTimes[PHASE_FILE_READING] += System.nanoTime() - start;
        }
    }

    public BibtexString parseString() throws IOException {
        long start = System.nanoTime();
        try {
            // Util.pr("Parsing string");
            skipWhitespace();
            consume('{', '(');
            // while (read() != '}');
            skipWhitespace();
            // Util.pr("Parsing string name");
            String name = parseTextToken();
            // Util.pr("Parsed string name");
            skipWhitespace();
            // Util.pr("Now the contents");
            consume('=');
            String content = parseFieldContent(name);
            // Util.pr("Now I'm going to consume a }");
            consume('}', ')');
            // Util.pr("Finished string parsing.");
            String id = Util.createNeutralId();
            return new BibtexString(id, name, content);
        } finally {
            _phaseTimes[PHASE_STRING_OPS] += System.nanoTime() - start;
        }
    }

    private String parsePreamble() throws IOException {
        long start = System.nanoTime();
        try {
            return parseBracketedText().toString();
        } finally {
            _phaseTimes[PHASE_STRING_OPS] += System.nanoTime() - start;
        }
    }

    public BibtexEntry parseEntry(BibtexEntryType tp) throws IOException {
        // Don't time the entire method since it includes other timed operations
        // Just do the core entry parsing logic without timing

        String id = Util.createNeutralId();
        BibtexEntry result = new BibtexEntry(id, tp);
        skipWhitespace();
        consume('{', '(');

        int c = peek();
        if ((c != '\n') && (c != '\r')) {
            skipWhitespace();
        }

        String key = parseKey();
        if ((key != null) && key.equals("")) {
            key = null;
        }
        result.setField(BibtexFields.KEY_FIELD, key);
        skipWhitespace();

        int fieldCount = 0;
        while (true) {
            c = peek();
            if ((c == '}') || (c == ')')) {
                break;
            }

            if (c == ',') {
                consume(',');
            }

            skipWhitespace();
            c = peek();
            if ((c == '}') || (c == ')')) {
                break;
            }

            parseField(result);
            fieldCount++;

            if (fieldCount > 100) { // Safety check
                break;
            }
        }

        consume('}', ')');
        return result;
    }

    private void parseField(BibtexEntry entry) throws IOException {
        long start = System.nanoTime();
        try {
            String key = parseTextToken().toLowerCase();
            // Util.pr("Field: _"+key+"_");
            skipWhitespace();
            consume('=');
            String content = parseFieldContent(key);
            // Now, if the field in question is set up to be fitted automatically
            // with braces around capitals, we should remove those now when reading the field:
            if (Globals.prefs.putBracesAroundCapitals(key)) {
                content = Util.removeBracesAroundCapitals(content);
            }
            if (content.length() > 0) {
                if (entry.getField(key) == null) {
                    entry.setField(key, content);
                } else {
                    // The following hack enables the parser to deal with multiple
                    // author or
                    // editor lines, stringing them together instead of getting just
                    // one of them.
                    // Multiple author or editor lines are not allowed by the bibtex
                    // format, but
                    // at least one online database exports bibtex like that, making
                    // it inconvenient
                    // for users if JabRef didn't accept it.
                    if (key.equals("author") || key.equals("editor")) {
                        entry.setField(key, entry.getField(key) + " and " + content);
                    }
                }
            }
        } finally {
            _phaseTimes[PHASE_FIELD_PARSING] += System.nanoTime() - start;
        }
    }

    private String parseFieldContent(String key) throws IOException {
        long start = System.nanoTime();
        try {

            skipWhitespace();
            StringBuilder value = new StringBuilder();
            int c = '.';

            while (((c = peek()) != ',') && (c != '}') && (c != ')')) {

                if (_eof) {
                    throw new RuntimeException("Error in line " + line + ": EOF in mid-string");
                }
                if (c == '"') {
                    StringBuffer text = parseQuotedFieldExactly();
                    value.append(fieldContentParser.format(text));
                    /*
				 * 
				 * The following code doesn't handle {"} correctly: // value is
				 * a string consume('"');
				 * 
				 * while (!((peek() == '"') && (j != '\\'))) { j = read(); if
				 * (_eof || (j == -1) || (j == 65535)) { throw new
				 * RuntimeException("Error in line "+line+ ": EOF in
				 * mid-string"); }
				 * 
				 * value.append((char) j); }
				 * 
				 * consume('"');
                     */
                } else if (c == '{') {
                    // Value is a string enclosed in brackets. There can be pairs
                    // of brackets inside of a field, so we need to count the
                    // brackets to know when the string is finished.
                    StringBuffer text = parseBracketedTextExactly();
                    value.append(fieldContentParser.format(text, key));

                } else if (Character.isDigit((char) c)) { // value is a number

                    String numString = parseTextToken();
                    // Morten Alver 2007-07-04: I don't see the point of parsing the integer
                    // and converting it back to a string, so I'm removing the construct below
                    // the following line:
                    value.append(numString);
                    /*
                try {
					// Fixme: What is this for?
					value.append(String.valueOf(Integer.parseInt(numString)));
				} catch (NumberFormatException e) {
					// If Integer could not be parsed then just add the text
					// Used to fix [ 1594123 ] Failure to import big numbers
					value.append(numString);
				}
                     */
                } else if (c == '#') {
                    consume('#');
                } else {
                    String textToken = parseTextToken();
                    if (textToken.length() == 0) {
                        throw new IOException("Error in line " + line + " or above: "
                                + "Empty text token.\nThis could be caused "
                                + "by a missing comma between two fields.");
                    }
                    value.append("#").append(textToken).append("#");
                    // Util.pr(parseTextToken());
                    // throw new RuntimeException("Unknown field type");
                }
                skipWhitespace();
            }
            // Util.pr("Returning field content: "+value.toString());

            // Check if we are to strip extra pairs of braces before returning:
            if (autoDoubleBraces) {
                // Do it:
                while ((value.length() > 1) && (value.charAt(0) == '{')
                        && (value.charAt(value.length() - 1) == '}')) {
                    value.deleteCharAt(value.length() - 1);
                    value.deleteCharAt(0);
                }
                // Problem: if the field content is "{DNA} blahblah {EPA}", one pair
                // too much will be removed.
                // Check if this is the case, and re-add as many pairs as needed.
                while (hasNegativeBraceCount(value.toString())) {
                    value.insert(0, '{');
                    value.append('}');
                }

            }
            return value.toString();
        } finally {
            _phaseTimes[PHASE_FIELD_CONTENT] += System.nanoTime() - start;
        }
    }

////////////////////////////////////////////////////////////////////////////
    private String parseFieldContentOptimized(String key) throws IOException {
        long start = System.nanoTime();
        try {
            skipWhitespace();
            StringBuilder value = new StringBuilder(1024); // Larger initial size

            int c;
            while (((c = peek()) != ',') && (c != '}') && (c != ')')) {
                if (_eof) {
                    throw new RuntimeException("Error in line " + line + ": EOF in mid-string");
                }

                if (c == '"') {
                    // Optimize quoted field parsing
                    StringBuffer text = parseQuotedFieldOptimized();
                    value.append(fieldContentParser.format(text));
                } else if (c == '{') {
                    // Use direct parsing without StringBuffer conversion
                    String text = parseBracketedTextOptimized();
                    value.append(fieldContentParser.format(new StringBuffer(text), key));
                } else if (Character.isDigit((char) c)) {
                    String numString = parseTextToken();
                    value.append(numString);
                } else if (c == '#') {
                    consume('#');
                } else {
                    String textToken = parseTextToken();
                    if (textToken.length() == 0) {
                        throw new IOException("Error in line " + line + " or above: "
                                + "Empty text token.");
                    }
                    value.append('#').append(textToken).append('#');
                }
                skipWhitespace();
            }

            // Optimize autoDoubleBraces processing
            return processAutoDoubleBraces(value);
        } finally {
            _phaseTimes[PHASE_FIELD_CONTENT] += System.nanoTime() - start;
        }
    }

    private String parseBracketedTextOptimized() throws IOException {
        long start = System.nanoTime();
        try {
            consume('{');
            StringBuilder value = new StringBuilder(2048); // Even larger for content

            int brackets = 1;
            int c;
            int bufferCount = 0;
            char[] buffer = new char[1024]; // Use char array for bulk appends

            while (brackets > 0) {
                c = read();
                if ((c == -1) || (c == 65535)) {
                    throw new RuntimeException("Error in line " + line + ": EOF in mid-string");
                }

                if (c == '{') {
                    brackets++;
                } else if (c == '}') {
                    brackets--;
                    if (brackets == 0) {
                        break;
                    }
                }

                // Bulk append optimization
                if (bufferCount >= buffer.length) {
                    value.append(buffer, 0, bufferCount);
                    bufferCount = 0;
                }
                buffer[bufferCount++] = (char) c;
            }

            // Append remaining characters
            if (bufferCount > 0) {
                value.append(buffer, 0, bufferCount);
            }

            return value.toString();
        } finally {
            _phaseTimes[PHASE_BRACKET_PARSING] += System.nanoTime() - start;
        }
    }

    private StringBuffer parseQuotedFieldOptimized() throws IOException {
        long start = System.nanoTime();
        try {
            consume('"');
            StringBuilder value = new StringBuilder(1024);

            int c;
            while (true) {
                c = read();
                if (c == '"') {
                    break;
                }
                if ((c == -1) || (c == 65535)) {
                    throw new RuntimeException("Error in line " + line + ": EOF in quoted string");
                }
                value.append((char) c);
            }

            return new StringBuffer(value.toString());
        } finally {
            // This time is included in FieldContent timing
        }
    }

    private String processAutoDoubleBraces(StringBuilder value) {
        if (!autoDoubleBraces) {
            return value.toString();
        }

        int len = value.length();
        int removeCount = 0;

        // Count how many outer brace pairs we can remove
        while (removeCount < len / 2
                && value.charAt(removeCount) == '{'
                && value.charAt(len - 1 - removeCount) == '}') {
            removeCount++;
        }

        if (removeCount > 0) {
            // Remove the outer braces
            value.delete(len - removeCount, len);
            value.delete(0, removeCount);

            // Check if we removed too many braces and need to add some back
            int braceBalance = 0;
            int minBalance = 0;

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '{') {
                    braceBalance++;
                } else if (c == '}') {
                    braceBalance--;
                    if (braceBalance < minBalance) {
                        minBalance = braceBalance;
                    }
                }
            }

            // If we have negative balance at any point, we need to add back some braces
            int bracesToAddBack = -minBalance;
            if (bracesToAddBack > 0) {
                for (int i = 0; i < bracesToAddBack; i++) {
                    value.insert(0, '{');
                    value.append('}');
                }
            }
        }

        return value.toString();
    }
////////////////////////////////////////////////////////////////////////////

    /**
     * Originalinhalt nach parseFieldContent(String) verschoben.
     *
     * @return
     * @throws IOException
     */
//	private String parseFieldContent() throws IOException {
//		return parseFieldContent(null);
//	}
    /**
     * Check if a string at any point has had more ending braces (}) than
     * opening ones ({). Will e.g. return true for the string "DNA} blahblal
     * {EPA"
     *
     * @param s The string to check.
     * @return true if at any index the brace count is negative.
     */
    private boolean hasNegativeBraceCount(String s) {
        // System.out.println(s);
        int i = 0, count = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '{') {
                count++;
            } else if (s.charAt(i) == '}') {
                count--;
            }
            if (count < 0) {
                return true;
            }
            i++;
        }
        return false;
    }

    /**
     * This method is used to parse string labels, field names, entry type and
     * numbers outside brackets.
     */
    private String parseTextToken() throws IOException {
        long start = System.nanoTime();
        try {

            StringBuilder token = new StringBuilder(32);

            while (true) {
                int c = read();
                // Util.pr(".. "+c);
                if (c == -1) {
                    _eof = true;
                    return token.toString();
                }

                // Optimized character checking
                char ch = (char) c;
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ":-_*+./'".indexOf(ch) >= 0) {
                    token.append(ch);
                } else {
                    unread(c);
                    return token.toString();
                }
            }
        } finally {
            _phaseTimes[PHASE_TEXT_TOKEN] += System.nanoTime() - start;
        }

    }

    /**
     * Tries to restore the key
     *
     * @return rest of key on success, otherwise empty string
     * @throws IOException on Reader-Error
     */
    private String fixKey() throws IOException {
        StringBuilder key = new StringBuilder();
        int lookahead_used = 0;
        char currentChar;

        // Find a char which ends key (','&&'\n') or entryfield ('='):
        do {
            currentChar = (char) read();
            key.append(currentChar);
            lookahead_used++;
        } while ((currentChar != ',' && currentChar != '\n' && currentChar != '=')
                && (lookahead_used < LOOKAHEAD));

        // Consumed a char too much, back into reader and remove from key:
        unread(currentChar);
        key.deleteCharAt(key.length() - 1);

        // Restore if possible:
        switch (currentChar) {
            case '=':

                // Get entryfieldname, push it back and take rest as key
                key = key.reverse();

                boolean matchedAlpha = false;
                for (int i = 0; i < key.length(); i++) {
                    currentChar = key.charAt(i);

                    /// Skip spaces:
                    if (!matchedAlpha && currentChar == ' ') {
                        continue;
                    }
                    matchedAlpha = true;

                    // Begin of entryfieldname (e.g. author) -> push back:
                    unread(currentChar);
                    if (currentChar == ' ' || currentChar == '\n') {

                        /*
                         * found whitespaces, entryfieldname completed -> key in
                         * keybuffer, skip whitespaces
                         */
                        StringBuilder newKey = new StringBuilder();
                        for (int j = i; j < key.length(); j++) {
                            currentChar = key.charAt(j);
                            if (!Character.isWhitespace(currentChar)) {
                                newKey.append(currentChar);
                            }
                        }

                        // Finished, now reverse newKey and remove whitespaces:
                        _pr.addWarning(Globals.lang("Line %0: Found corrupted BibTeX-key.",
                                String.valueOf(line)));
                        key = newKey.reverse();
                    }
                }
                break;

            case ',':

                _pr.addWarning(Globals.lang("Line %0: Found corrupted BibTeX-key (contains whitespaces).",
                        String.valueOf(line)));

            case '\n':

                _pr.addWarning(Globals.lang("Line %0: Found corrupted BibTeX-key (comma missing).",
                        String.valueOf(line)));

                break;

            default:

                // No more lookahead, give up:
                unreadBuffer(key);
                return "";
        }

        return removeWhitespaces(key).toString();
    }

    /**
     * removes whitespaces from <code>sb</code>
     *
     * @param sb
     * @return
     */
    private StringBuilder removeWhitespaces(StringBuilder sb) {
        StringBuilder newSb = new StringBuilder();
        char current;
        for (int i = 0; i < sb.length(); ++i) {
            current = sb.charAt(i);
            if (!Character.isWhitespace(current)) {
                newSb.append(current);
            }
        }
        return newSb;
    }

    /**
     * pushes buffer back into input
     *
     * @param sb
     * @throws IOException can be thrown if buffer is bigger than LOOKAHEAD
     */
    private void unreadBuffer(StringBuilder sb) throws IOException {
        for (int i = sb.length() - 1; i >= 0; --i) {
            unread(sb.charAt(i));
        }
    }

    /**
     * This method is used to parse the bibtex key for an entry.
     */
    private String parseKey() throws IOException {
        StringBuilder token = new StringBuilder(20);

        while (true) {
            int c = read();
            // Util.pr(".. '"+(char)c+"'\t"+c);
            if (c == -1) {
                _eof = true;

                return token.toString();
            }

            // Ikke: #{}\uFFFD~\uFFFD
            //
            // G\uFFFDr: $_*+.-\/?"^
            if (!Character.isWhitespace((char) c)
                    && (Character.isLetterOrDigit((char) c) || (c == ':') || ((c != '#') && (c != '{') && (c != '}')
                    && (c != '\uFFFD') && (c != '~') && (c != '\uFFFD') && (c != ',') && (c != '=')))) {
                token.append((char) c);
            } else {

                if (Character.isWhitespace((char) c)) {
                    // We have encountered white space instead of the comma at
                    // the end of
                    // the key. Possibly the comma is missing, so we try to
                    // return what we
                    // have found, as the key and try to restore the rest in fixKey().
                    return token.toString() + fixKey();
                } else if (c == ',') {
                    unread(c);
                    return token.toString();
                    // } else if (Character.isWhitespace((char)c)) {
                    // throw new NoLabelException(token.toString());
                } else if (c == '=') {
                    // If we find a '=' sign, it is either an error, or
                    // the entry lacked a comma signifying the end of the key.

                    return token.toString();
                    // throw new NoLabelException(token.toString());

                } else {
                    throw new IOException("Error in line " + line + ":" + "Character '" + (char) c
                            + "' is not " + "allowed in bibtex keys.");
                }

            }
        }

    }

    private class NoLabelException extends Exception {

        public NoLabelException(String hasRead) {
            super(hasRead);
        }
    }

    private StringBuffer parseBracketedText() throws IOException {
        // Util.pr("Parse bracketed text");
        StringBuffer value = new StringBuffer();

        consume('{');

        int brackets = 0;

        while (!((peek() == '}') && (brackets == 0))) {

            int j = read();
            if ((j == -1) || (j == 65535)) {
                throw new RuntimeException("Error in line " + line + ": EOF in mid-string");
            } else if (j == '{') {
                brackets++;
            } else if (j == '}') {
                brackets--;
            }

            // If we encounter whitespace of any kind, read it as a
            // simple space, and ignore any others that follow immediately.
            /*
			 * if (j == '\n') { if (peek() == '\n') value.append('\n'); } else
             */
            if (Character.isWhitespace((char) j)) {
                String whs = skipAndRecordWhitespace(j);

                // System.out.println(":"+whs+":");
                if (!whs.equals("") && !whs.equals("\n\t")) { // &&
                    // !whs.equals("\n"))

                    whs = whs.replaceAll("\t", ""); // Remove tabulators.

                    // while (whs.endsWith("\t"))
                    // whs = whs.substring(0, whs.length()-1);
                    value.append(whs);

                } else {
                    value.append(' ');
                }

            } else {
                value.append((char) j);
            }

        }

        consume('}');

        return value;
    }

    private StringBuffer parseBracketedTextExactly() throws IOException {
        long start = System.nanoTime();
        try {
            consume('{');
            StringBuilder value = new StringBuilder(256);
            int brackets = 1;

            while (brackets > 0) {
                int j = read();
                if ((j == -1) || (j == 65535)) {
                    throw new RuntimeException("Error in line " + line + ": EOF in mid-string");
                }

                if (j == '{') {
                    brackets++;
                } else if (j == '}') {
                    brackets--;
                    if (brackets > 0) {
                        value.append((char) j);
                    }
                    continue;
                }

                if (brackets > 0) {
                    value.append((char) j);
                }
            }

            return new StringBuffer(value.toString());
        } finally {
            _phaseTimes[PHASE_BRACKET_PARSING] += System.nanoTime() - start;
        }
    }

    private StringBuffer parseQuotedFieldExactly() throws IOException {

        StringBuffer value = new StringBuffer();

        consume('"');

        int brackets = 0;

        while (!((peek() == '"') && (brackets == 0))) {

            int j = read();
            if ((j == -1) || (j == 65535)) {
                throw new RuntimeException("Error in line " + line + ": EOF in mid-string");
            } else if (j == '{') {
                brackets++;
            } else if (j == '}') {
                brackets--;
            }

            value.append((char) j);

        }

        consume('"');

        return value;
    }

    private void consume(char expected) throws IOException {
        int c = read();

        if (c != expected) {
            throw new RuntimeException("Error in line " + line + ": Expected " + expected
                    + " but received " + (char) c);
        }

    }

    private boolean consumeUncritically(char expected) throws IOException {
        int c;
        while (((c = read()) != expected) && (c != -1) && (c != 65535)) {
            // do nothing
        }

        if ((c == -1) || (c == 65535)) {
            _eof = true;
        }

        // Return true if we actually found the character we were looking for:
        return c == expected;
    }

    private void consume(char expected1, char expected2) throws IOException {
        // Consumes one of the two, doesn't care which appears.

        int c = read();

        if ((c != expected1) && (c != expected2)) {
            throw new RuntimeException("Error in line " + line + ": Expected " + expected1 + " or "
                    + expected2 + " but received " + c);

        }

    }

    public void checkEntryTypes(ParserResult _pr) {

        for (BibtexEntry be : _db.getEntries()) {
            if (be.getType() instanceof UnknownEntryType) {
                // Look up the unknown type name in our map of parsed types:

                Object o = entryTypes.get(be.getType().getName().toLowerCase());
                if (o != null) {
                    BibtexEntryType type = (BibtexEntryType) o;
                    be.setType(type);
                } else {
                    // System.out.println("Unknown entry type:
                    // "+be.getType().getName());
                    _pr
                            .addWarning(Globals.lang("unknown entry type") + ": "
                                    + be.getType().getName() + ":" + be.getField(BibtexFields.KEY_FIELD)
                                    + " . " + Globals.lang("Type set to 'other'")
                                    + ".");
                    be.setType(BibtexEntryType.OTHER);
                }
            }
        }
    }

    /**
     * Read the JabRef signature, if any, and find what version number is given.
     * This method advances the file reader only as far as the end of the first
     * line of the JabRef signature, or up until the point where the read
     * characters don't match the signature. This should ensure that the parser
     * can continue from that spot without resetting the reader, without the
     * risk of losing important contents.
     *
     * @return The version number, or null if not found.
     * @throws IOException
     */
    private String readJabRefVersionNumber() throws IOException {
        StringBuilder headerText = new StringBuilder();

        boolean keepon = true;
        int piv = 0;
        int c;

        // We start by reading the standard part of the signature, which precedes
        // the version number:
        //                     This file was created with JabRef X.y.
        while (keepon) {
            c = peek();
            headerText.append((char) c);
            if ((piv == 0) && (Character.isWhitespace((char) c) || (c == '%'))) {
                read();
            } else if (c == GUIGlobals.SIGNATURE.charAt(piv)) {
                piv++;
                read();
            } else {
                keepon = false;
                return null;
            }

            // Check if we've reached the end of the signature's standard part:
            if (piv == GUIGlobals.SIGNATURE.length()) {
                keepon = false;

                // Found the standard part. Now read the version number:
                StringBuilder sb = new StringBuilder();
                while (((c = read()) != '\n') && (c != -1)) {
                    sb.append((char) c);
                }
                String versionNum = sb.toString().trim();
                // See if it fits the X.y. pattern:
                if (Pattern.compile("[1-9]+\\.[1-9A-Za-z ]+\\.").matcher(versionNum).matches()) {
                    // It matched. Remove the last period and return:
                    return versionNum.substring(0, versionNum.length() - 1);
                } else if (Pattern.compile("[1-9]+\\.[1-9]\\.[1-9A-Za-z ]+\\.").matcher(versionNum).matches()) {
                    // It matched. Remove the last period and return:
                    return versionNum.substring(0, versionNum.length() - 1);
                }

            }
        }

        return null;
    }

    /**
     * After a JabRef version number has been parsed and put into _pr, parse the
     * version number to determine the JabRef major and minor version number
     */
    private void setMajorMinorVersions() {
        String v = _pr.getJabrefVersion();
        Pattern p = Pattern.compile("([0-9]+)\\.([0-9]+).*");
        Pattern p2 = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+).*");
        Matcher m = p.matcher(v);
        Matcher m2 = p2.matcher(v);
        if (m.matches()) {
            if (m.groupCount() >= 2) {
                _pr.setJabrefMajorVersion(Integer.parseInt(m.group(1)));
                _pr.setJabrefMinorVersion(Integer.parseInt(m.group(2)));
            }
        }
        if (m2.matches()) {
            if (m2.groupCount() >= 3) {
                _pr.setJabrefMinor2Version(Integer.parseInt(m2.group(3)));
            }
        }
    }
}
