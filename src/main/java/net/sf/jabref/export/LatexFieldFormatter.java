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
package net.sf.jabref.export;

import java.util.HashSet;
import java.util.Set;

import net.sf.jabref.*;

public class LatexFieldFormatter implements FieldFormatter {

    private static final boolean PERF_TIMERS = false;
    private static final int PERF_SUMMARY_EVERY_CALLS = 50000;

    private static long totalFormatCalls;
    private static long totalFormatNanos;
    private static long totalCheckBracesNanos;
    private static long totalWriteTextNanos;
    private static long totalWrapNanos;
    private static long totalCharsInput;
    private static long totalCharsOutput;
    private static long totalResolveStringsFalse;

    public static LatexFieldFormatter buildIgnoreHashes() {
        return new LatexFieldFormatter(true);
    }

    StringBuilder sb;
    int col; // First line usually starts about so much further to the right.
    final int STARTCOL = 4;

    private final boolean neverFailOnHashes;

    private final boolean resolveStringsAllFields;
    private final char valueDelimitersZero;
    private final char valueDelimitersOne;
    private final boolean writefieldWrapfield;
    private final String[] doNotResolveStringsFors;
    private final Set<String> doNotResolveStringsForSet = new HashSet<String>();

    public LatexFieldFormatter() {
        this(true);
    }

    private LatexFieldFormatter(boolean neverFailOnHashes) {
        this.neverFailOnHashes = neverFailOnHashes;

        this.resolveStringsAllFields = Globals.prefs.getBoolean("resolveStringsAllFields");
        valueDelimitersZero = Globals.prefs.getValueDelimiters(0);
        valueDelimitersOne = Globals.prefs.getValueDelimiters(1);
        doNotResolveStringsFors = Globals.prefs.getStringArray("doNotResolveStringsFor");
        if (doNotResolveStringsFors != null) {
            for (String field : doNotResolveStringsFors) {
                doNotResolveStringsForSet.add(field);
            }
        }
        writefieldWrapfield = Globals.prefs.getBoolean(JabRefPreferences.WRITEFIELD_WRAPFIELD);
    }

    public String format(String text, String fieldName)
            throws IllegalArgumentException {
        long formatStart = System.nanoTime();
        String result = null;
        int inputLength = (text == null ? 0 : text.length());
        boolean countedResolveFalse = false;
        try {
            if (text == null) {
                result = valueDelimitersZero + "" + valueDelimitersOne;
                return result;
            }

            if (Globals.prefs.putBracesAroundCapitals(fieldName) && !Globals.BIBTEX_STRING.equals(fieldName)) {
                text = Util.putBracesAroundCapitals(text);
            }

            // normalize newlines. Use String.replace, not replaceAll, to avoid regex overhead.
            if (!text.contains(Globals.NEWLINE) && text.contains("\n")) {
                // if we don't have real new lines, but pseudo newlines, we replace them
                // On Win 8.1, this is always true for multiline fields
                text = text.replace("\n", Globals.NEWLINE);
            }

            // If the field is non-standard, we will just append braces,
            // wrap and write.
            boolean resolveStrings;
            if (resolveStringsAllFields) {
                // Resolve strings for all fields except some:
                resolveStrings = !doNotResolveStringsForSet.contains(fieldName);
            } else {
                // Default operation - we only resolve strings for standard fields:
                resolveStrings = BibtexFields.isStandardField(fieldName)
                        || Globals.BIBTEX_STRING.equals(fieldName);
            }
            boolean nonWrappableField = Globals.prefs.isNonWrappableField(fieldName);
            if (!resolveStrings) {
                countedResolveFalse = true;
                checkBalancedBraces(text);

                sb = new StringBuilder();
                sb.append(valueDelimitersZero);
                // No formatting at all for these fields, to allow custom formatting?
//            if (Globals.prefs.getBoolean("preserveFieldFormatting"))
//              sb.append(text);
//            else
//             currently, we do not do any more wrapping
                if (writefieldWrapfield && !nonWrappableField) {
                    long wrapStart = System.nanoTime();
                    sb.append(Util.wrap2(text, GUIGlobals.LINE_LENGTH));
                    totalWrapNanos += System.nanoTime() - wrapStart;
                } else {
                    sb.append(text);
                }

                sb.append(valueDelimitersOne);

                result = sb.toString();
                return result;
            }

            sb = new StringBuilder(text.length() + 8);
            int pivot = 0;
            int pos1;
            int pos2;
            col = STARTCOL;
            // Here we assume that the user encloses any bibtex strings in #, e.g.:
            // #jan# - #feb#
            // ...which will be written to the file like this:
            // jan # { - } # feb
            long checkStart = System.nanoTime();
            checkBraces(text);
            totalCheckBracesNanos += System.nanoTime() - checkStart;

            while (pivot < text.length()) {
                int goFrom = pivot;
                pos1 = pivot;
                while (goFrom == pos1) {
                    pos1 = text.indexOf('#', goFrom);
                    if ((pos1 > 0) && (text.charAt(pos1 - 1) == '\\')) {
                        goFrom = pos1 + 1;
                        pos1++;
                    } else {
                        goFrom = pos1 - 1; // Ends the loop.
                    }
                }

                if (pos1 == -1) {
                    pos1 = text.length(); // No more occurrences found.
                    pos2 = -1;
                } else {
                    pos2 = text.indexOf('#', pos1 + 1);
                    if (pos2 == -1) {
                        if (!neverFailOnHashes) {
                            throw new IllegalArgumentException(Globals.lang("The # character is not allowed in BibTeX strings unless escaped as in '\\#'.") + "\n"
                                    + Globals.lang("In JabRef, use pairs of # characters to indicate a string.") + "\n"
                                    + Globals.lang("Note that the entry causing the problem has been selected."));
                        } else {
                            pos1 = text.length(); // just write out the rest of the text, and throw no exception
                        }
                    }
                }

                if (pos1 > pivot) {
                    writeText(text, pivot, pos1);
                }
                if ((pos1 < text.length()) && (pos2 - 1 > pos1)) // We check that the string label is not empty. That means
                // an occurrence of ## will simply be ignored. Should it instead
                // cause an error message?
                {
                    writeStringLabel(text, pos1 + 1, pos2, (pos1 == pivot),
                            (pos2 + 1 == text.length()));
                }

                if (pos2 > -1) {
                    pivot = pos2 + 1;
                } else {
                    pivot = pos1 + 1;
                }
                //if (tell++ > 10) System.exit(0);
            }

            // currently, we do not add newlines and new formatting
            if (writefieldWrapfield && !nonWrappableField) {
//             introduce a line break to be read at the parser
                long wrapStart = System.nanoTime();
                result = Util.wrap2(sb.toString(), GUIGlobals.LINE_LENGTH);//, but that lead to ugly .tex
                totalWrapNanos += System.nanoTime() - wrapStart;
                return result;

            } else {
                result = sb.toString();
                return result;
            }
        } finally {
            if (PERF_TIMERS) {
                totalFormatCalls++;
                totalFormatNanos += System.nanoTime() - formatStart;
                totalCharsInput += inputLength;
                if (result != null) {
                    totalCharsOutput += result.length();
                }
                if (countedResolveFalse) {
                    totalResolveStringsFalse++;
                }
                if (totalFormatCalls % PERF_SUMMARY_EVERY_CALLS == 0) {
                    printPerfSummary("periodic");
                }
            }
        }
    }

    private void writeText(String text, int start_pos,
            int end_pos) {
        long writeTextStart = System.nanoTime();
        try {
            /*sb.append("{");
            sb.append(text.substring(start_pos, end_pos));
            sb.append("}");*/
            sb.append(valueDelimitersZero);
            boolean escape = false, inCommandName = false, inCommand = false,
                    inCommandOption = false;
            int nestedEnvironments = 0;
            StringBuilder commandName = new StringBuilder();
            char c;
            for (int i = start_pos; i < end_pos; i++) {
                c = text.charAt(i);

                // Track whether we are in a LaTeX command of some sort.
                if (Character.isLetter(c) && (escape || inCommandName)) {
                    inCommandName = true;
                    if (!inCommandOption) {
                        commandName.append(c);
                    }
                } else if (Character.isWhitespace(c) && (inCommand || inCommandOption)) {
                    //System.out.println("whitespace here");
                } else if (inCommandName) {
                    // This means the command name is ended.
                    // Perhaps the beginning of an argument:
                    if (c == '[') {
                        inCommandOption = true;
                    } // Or the end of an argument:
                    else if (inCommandOption && (c == ']')) {
                        inCommandOption = false;
                    } // Or the beginning of the command body:
                    else if (!inCommandOption && (c == '{')) {
                        //System.out.println("Read command: '"+commandName.toString()+"'");
                        inCommandName = false;
                        inCommand = true;
                    } // Or simply the end of this command altogether:
                    else {
                        //System.out.println("I think I read command: '"+commandName.toString()+"'");

                        commandName.delete(0, commandName.length());
                        inCommandName = false;
                    }
                }
                // If we are in a command body, see if it has ended:
                if (inCommand && (c == '}')) {
                    //System.out.println("nestedEnvironments = " + nestedEnvironments);
                    //System.out.println("Done with command: '"+commandName.toString()+"'");
                    if (commandNameEquals(commandName, "begin")) {
                        nestedEnvironments++;
                    }
                    if (nestedEnvironments > 0 && commandNameEquals(commandName, "end")) {
                        nestedEnvironments--;
                    }
                    //System.out.println("nestedEnvironments = " + nestedEnvironments);

                    commandName.delete(0, commandName.length());
                    inCommand = false;
                }

                // We add a backslash before any ampersand characters, with one exception: if
                // we are inside an \\url{...} command, we should write it as it is. Maybe.
                if ((c == '&') && !escape
                        && !(inCommand && commandNameEquals(commandName, "url"))
                        && (nestedEnvironments == 0)) {
                    sb.append("\\&");
                } else {
                    sb.append(c);
                }
                escape = (c == '\\');
            }
            sb.append(valueDelimitersOne);
        } finally {
            totalWriteTextNanos += System.nanoTime() - writeTextStart;
        }
    }

    private void writeStringLabel(String text, int start_pos, int end_pos,
            boolean first, boolean last) {
        //sb.append(Util.wrap2((first ? "" : " # ") + text.substring(start_pos, end_pos)
        //             + (last ? "" : " # "), GUIGlobals.LINE_LENGTH));
        putIn((first ? "" : " # ") + text.substring(start_pos, end_pos)
                + (last ? "" : " # "));
    }

    private void putIn(String s) {
        long wrapStart = System.nanoTime();
        sb.append(Util.wrap2(s, GUIGlobals.LINE_LENGTH));
        totalWrapNanos += System.nanoTime() - wrapStart;
    }

    private void checkBraces(String text) throws IllegalArgumentException {
        int balance = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                balance++;
            } else if (c == '}') {
                if (balance == 0) {
                    throw new IllegalArgumentException("'}' character ends string prematurely.");
                }
                balance--;
            }
        }
        if (balance != 0) {
            throw new IllegalArgumentException("Braces don't match.");
        }
    }

    private void checkBalancedBraces(String text) throws IllegalArgumentException {
        int brc = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                brc++;
            } else if (c == '}') {
                brc--;
                if (brc < 0) {
                    throw new IllegalArgumentException("Curly braces { and } must be balanced.");
                }
            }
        }
        if (brc != 0) {
            throw new IllegalArgumentException("Curly braces { and } must be balanced.");
        }
    }

    private static boolean commandNameEquals(StringBuilder commandName, String value) {
        if (commandName.length() != value.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (commandName.charAt(i) != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static void printPerfSummary(String reason) {
        System.out.println("[LatexFieldFormatter timer] " + reason
                + " calls=" + totalFormatCalls
                + ", inputChars=" + totalCharsInput
                + ", outputChars=" + totalCharsOutput
                + ", resolveStringsFalse=" + totalResolveStringsFalse
                + ", totalMs=" + nanosToMs(totalFormatNanos)
                + ", checkBracesMs=" + nanosToMs(totalCheckBracesNanos)
                + ", writeTextMs=" + nanosToMs(totalWriteTextNanos)
                + ", wrapMs=" + nanosToMs(totalWrapNanos)
                + ", thread=" + Thread.currentThread().getName()
                + ", edt=" + isEventDispatchThread());
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1000000L;
    }

    private static boolean isEventDispatchThread() {
        try {
            return javax.swing.SwingUtilities.isEventDispatchThread();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
