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

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.TreeSet;

/**
 * Writer that extends OutputStreamWriter, but also checks if the chosen
 * encoding supports all text that is written. Currently only a boolean value is
 * stored to remember whether everything has gone well or not.
 */
public class VerifyingWriter extends OutputStreamWriter {

    private static final boolean PERF_TIMERS = true;

    CharsetEncoder encoder;
    private boolean couldEncodeAll = true;
    private TreeSet<Character> problemCharacters = new TreeSet<Character>();
    private final String encodingName;
    private final boolean skipEncodingVerification;

    private long writeCalls;
    private long charsWritten;
    private long delegateWriteNanos;
    private long verifyNanos;
    private long failedStringChecks;
    private long failedCharChecks;
    private boolean summaryPrinted;

    public VerifyingWriter(OutputStream out, String encoding)
            throws UnsupportedEncodingException {
        super(out, encoding);
        Charset charset = Charset.forName(encoding);
        encoder = charset.newEncoder();
        encodingName = charset.name();
        skipEncodingVerification = isAlwaysUnicodeEncoding(encodingName);
    }

    public void write(String str) throws IOException {
        long start = System.nanoTime();
        super.write(str);
        delegateWriteNanos += System.nanoTime() - start;

        writeCalls++;
        if (str != null) {
            charsWritten += str.length();
        }

        if (!skipEncodingVerification) {
            long verifyStart = System.nanoTime();
            if (!encoder.canEncode(str)) {
                failedStringChecks++;
                for (int i = 0; i < str.length(); i++) {
                    if (!encoder.canEncode(str.charAt(i))) {
                        problemCharacters.add(str.charAt(i));
                        failedCharChecks++;
                    }
                }
                couldEncodeAll = false;
            }
            verifyNanos += System.nanoTime() - verifyStart;
        }
    }

    public void close() throws IOException {
        try {
            super.close();
        } finally {
            printSummary("close");
        }
    }

    public boolean couldEncodeAll() {
        return couldEncodeAll;
    }

    public String getProblemCharacters() {
        StringBuilder chars = new StringBuilder();
        for (Character ch : problemCharacters) {
            chars.append(ch.charValue());
        }
        return chars.toString();
    }

    private static boolean isAlwaysUnicodeEncoding(String canonicalName) {
        return "UTF-8".equalsIgnoreCase(canonicalName)
                || "UTF-16".equalsIgnoreCase(canonicalName)
                || "UTF-16BE".equalsIgnoreCase(canonicalName)
                || "UTF-16LE".equalsIgnoreCase(canonicalName);
    }

    private void printSummary(String reason) {
        if (!PERF_TIMERS || summaryPrinted) {
            return;
        }
        summaryPrinted = true;
        System.out.println("[VerifyingWriter timer] " + reason
                + " encoding=" + encodingName
                + ", skipEncodingVerification=" + skipEncodingVerification
                + ", writeCalls=" + writeCalls
                + ", charsWritten=" + charsWritten
                + ", delegateWriteMs=" + nanosToMs(delegateWriteNanos)
                + ", verifyMs=" + nanosToMs(verifyNanos)
                + ", failedStringChecks=" + failedStringChecks
                + ", failedCharChecks=" + failedCharChecks
                + ", problemChars=" + problemCharacters.size()
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
