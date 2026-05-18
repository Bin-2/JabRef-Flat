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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import javax.swing.SwingUtilities;
import java.util.regex.Pattern;

import net.sf.jabref.*;
import net.sf.jabref.config.SaveOrderConfig;

public class FileActions {

    public enum DatabaseSaveType {
        DEFAULT, PLAIN_BIBTEX
    }

    private static Pattern refPat = Pattern.compile("(#[A-Za-z]+#)"); // Used to detect string references in strings
    private static BibtexString.Type previousStringType;

    private static final boolean PERF_TIMERS = true;
    private static final long PERF_LOG_THRESHOLD_MS = 500L;

    private static long perfStart() {
        return System.nanoTime();
    }

    private static void logPerf(String label, long startNanos) {
        logPerf(label, startNanos, null);
    }

    private static void logPerf(String label, long startNanos, String details) {
        if (!PERF_TIMERS) {
            return;
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        long elapsedMillis = elapsedNanos / 1000000L;
        if (elapsedMillis < PERF_LOG_THRESHOLD_MS) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("[FileActions timer] ");
        sb.append(label);
        if ((details != null) && (details.length() > 0)) {
            sb.append(' ');
            sb.append(details);
        }
        sb.append(" took ");
        sb.append(elapsedMillis);
        sb.append(" ms (");
        sb.append(elapsedNanos);
        sb.append(" ns) thread=");
        sb.append(Thread.currentThread().getName());
        sb.append(" edt=");
        sb.append(SwingUtilities.isEventDispatchThread());
        System.out.println(sb.toString());
    }

    private static long fileSize(File file) {
        if ((file == null) || !file.exists()) {
            return -1L;
        }
        return file.length();
    }

    private static String fileDetails(File file) {
        if (file == null) {
            return "file=null";
        }
        return "file=" + file.getPath() + " exists=" + file.exists() + " bytes=" + fileSize(file);
    }


    private static void writePreamble(Writer fw, String preamble) throws IOException {
        if (preamble != null) {
            fw.write("@PREAMBLE{");
            fw.write(preamble);
            fw.write("}" + Globals.NEWLINE + Globals.NEWLINE);
        }
    }

    /**
     * Write all strings in alphabetical order, modified to produce a safe (for
     * BibTeX) order of the strings if they reference each other.
     *
     * @param fw The Writer to send the output to.
     * @param database The database whose strings we should write.
     * @throws IOException If anthing goes wrong in writing.
     */
    private static void writeStrings(Writer fw, BibtexDatabase database) throws IOException {
        long totalStart = perfStart();
        previousStringType = BibtexString.Type.AUTHOR;

        long keySetStart = perfStart();
        Set<String> stringKeySet = database.getStringKeySet();
        int stringKeyCount = stringKeySet.size();
        logPerf("writeStrings getStringKeySet", keySetStart, "keys=" + stringKeyCount);

        long collectStart = perfStart();
        List<BibtexString> strings = new ArrayList<BibtexString>(stringKeyCount);
        for (String s : stringKeySet) {
            strings.add(database.getString(s));
        }
        logPerf("writeStrings collect strings", collectStart, "strings=" + strings.size());

        long sortStart = perfStart();
        Collections.sort(strings, new BibtexStringComparator(true));
        logPerf("writeStrings sort strings", sortStart, "strings=" + strings.size());

        // First, make a Map of all entries:
        long mapStart = perfStart();
        HashMap<String, BibtexString> remaining = new HashMap<String, BibtexString>(Math.max(16, strings.size() * 2));
        int maxKeyLength = 0;
        for (BibtexString string : strings) {
            remaining.put(string.getName(), string);
            maxKeyLength = Math.max(maxKeyLength, string.getName().length());
        }
        logPerf("writeStrings build remaining map", mapStart, "strings=" + strings.size());

        long writeStart = perfStart();
        int writtenStrings = 0;
        for (BibtexString.Type t : BibtexString.Type.values()) {
            for (BibtexString bs : strings) {
                if (remaining.containsKey(bs.getName()) && bs.getType() == t) {
                    writeString(fw, bs, remaining, maxKeyLength);
                    writtenStrings++;
                }
            }
        }
        fw.write(Globals.NEWLINE);
        logPerf("writeStrings write loop", writeStart, "writtenTopLevel=" + writtenStrings + ", remaining=" + remaining.size());
        logPerf("writeStrings total", totalStart, "strings=" + strings.size());
    }

    private static void writeString(Writer fw, BibtexString bs, HashMap<String, BibtexString> remaining, int maxKeyLength) throws IOException {
        // First remove this from the "remaining" list so it can't cause problem with circular refs:
        remaining.remove(bs.getName());

        // Then we go through the string looking for references to other strings. If we find references
        // to strings that we will write, but still haven't, we write those before proceeding. This ensures
        // that the string order will be acceptable for BibTeX.
        String content = bs.getContent();
        Matcher m;
        while ((m = refPat.matcher(content)).find()) {
            String foundLabel = m.group(1);
            int restIndex = content.indexOf(foundLabel) + foundLabel.length();
            content = content.substring(restIndex);
            Object referred = remaining.get(foundLabel.substring(1, foundLabel.length() - 1));
            // If the label we found exists as a key in the "remaining" Map, we go on and write it now:
            if (referred != null) {
                writeString(fw, (BibtexString) referred, remaining, maxKeyLength);
            }
        }

        if (previousStringType != bs.getType()) {
            fw.write(Globals.NEWLINE);
            previousStringType = bs.getType();
        }

//        long startTime = System.currentTimeMillis();
//        String suffix0 = "";
//        for (int i = maxKeyLength - bs.getName().length(); i > 0; i--) {
//            suffix0 += " ";
//        }
//        long sortTime = System.currentTimeMillis() - startTime;
//        System.out.println("string building original: " + sortTime + "ms");
//******************************************************************************
        int spacesNeeded = Math.max(0, maxKeyLength - bs.getName().length());
        StringBuilder sb = new StringBuilder(spacesNeeded);
        for (int i = 0; i < spacesNeeded; i++) {
            sb.append(' ');
        }
        String suffix = sb.toString();

        fw.write("@String { " + bs.getName() + suffix + " = ");
        if (!bs.getContent().equals("")) {
            try {
                String formatted = (new LatexFieldFormatter()).format(bs.getContent(), Globals.BIBTEX_STRING);
                fw.write(formatted);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        Globals.lang("The # character is not allowed in BibTeX strings unless escaped as in '\\#'.") + "\n"
                        + Globals.lang("Before saving, please edit any strings containing the # character."));
            }

        } else {
            fw.write("{}");
        }

        fw.write(" }" + Globals.NEWLINE);// + Globals.NEWLINE);
    }

    /**
     * Writes the JabRef signature and the encoding.
     *
     * @param encoding String the name of the encoding, which is part of the
     * header.
     */
    private static void writeBibFileHeader(Writer out, String encoding) throws IOException {
        out.write("% ");
        out.write(GUIGlobals.SIGNATURE);
        out.write(" " + GUIGlobals.version + "." + Globals.NEWLINE + "% "
                + GUIGlobals.encPrefix + encoding + Globals.NEWLINE + Globals.NEWLINE);
    }

    /**
     * Saves the database to file. Two boolean values indicate whether only
     * entries with a nonzero Globals.SEARCH value and only entries with a
     * nonzero Globals.GROUPSEARCH value should be saved. This can be used to
     * let the user save only the results of a search. False and false means all
     * entries are saved.
     */
    public static SaveSession saveDatabase(BibtexDatabase database,
            MetaData metaData, File file, JabRefPreferences prefs,
            boolean checkSearch, boolean checkGroup, String encoding, boolean suppressBackup)
            throws SaveException {

        long totalStart = perfStart();
        int entryCount = database.getEntryCount();
        TreeMap<String, BibtexEntryType> types = new TreeMap<String, BibtexEntryType>();

        boolean backup = prefs.getBoolean("backup");
        if (suppressBackup) {
            backup = false;
        }
        System.out.println("[FileActions timer] saveDatabase start entries=" + entryCount
                + " checkSearch=" + checkSearch
                + " checkGroup=" + checkGroup
                + " backup=" + backup
                + " encoding=" + encoding
                + " " + fileDetails(file)
                + " thread=" + Thread.currentThread().getName()
                + " edt=" + SwingUtilities.isEventDispatchThread());

        SaveSession session;
        BibtexEntry exceptionCause = null;
        long sessionStart = perfStart();
        try {
            session = new SaveSession(file, encoding, backup);
        } catch (Throwable e) {
            logPerf("saveDatabase new SaveSession failed", sessionStart, "encoding=" + encoding + " " + fileDetails(file));
            if (encoding != null) {
                System.err.println("Error from encoding: '" + encoding + "' Len: " + encoding.length());
            }
            // we must catch all exceptions to be able notify users that
            // saving failed, no matter what the reason was
            // (and they won't just quit JabRef thinking
            // everyting worked and loosing data)
            e.printStackTrace();
            throw new SaveException(e.getMessage());
        }
        logPerf("saveDatabase new SaveSession", sessionStart, "tmpBytes=" + fileSize(session.getTemporaryFile()));

        try {

            // Get our data stream. This stream writes only to a temporary file,
            // until committed.
            long writerStart = perfStart();
            VerifyingWriter fw = session.getWriter();
            logPerf("saveDatabase getWriter", writerStart);

            // Write signature.
            long headerStart = perfStart();
            writeBibFileHeader(fw, encoding);
            logPerf("saveDatabase writeBibFileHeader", headerStart);

            // Write preamble if there is one.
            String preamble = database.getPreamble();
            long preambleStart = perfStart();
            writePreamble(fw, preamble);
            logPerf("saveDatabase writePreamble", preambleStart, "hasPreamble=" + (preamble != null));

            // Write strings if there are any.
            long stringsStart = perfStart();
            writeStrings(fw, database);
            logPerf("saveDatabase writeStrings", stringsStart);

            // Write database entries. Take care, using CrossRefEntry-
            // Comparator, that referred entries occur after referring
            // ones. Apart from crossref requirements, entries will be
            // sorted as they appear on the screen.
            long sortStart = perfStart();
            List<BibtexEntry> sorter = getSortedEntries(database, metaData, null, true);
            logPerf("saveDatabase getSortedEntries", sortStart, "sortedEntries=" + sorter.size());

            long entryWriterStart = perfStart();
            BibtexEntryWriter bibtexEntryWriter = new BibtexEntryWriter(new LatexFieldFormatter(), true);
            logPerf("saveDatabase new BibtexEntryWriter", entryWriterStart);

            long writeStart = perfStart();
            int consideredEntries = 0;
            int writtenEntries = 0;
            int skippedSearch = 0;
            int skippedGroup = 0;
            int customTypeHits = 0;
            for (BibtexEntry be : sorter) {
                consideredEntries++;
                exceptionCause = be;

                // Check if we must write the type definition for this
                // entry, as well. Our criterion is that all non-standard
                // types (*not* customized standard types) must be written.
                BibtexEntryType tp = be.getType();

                if (BibtexEntryType.getStandardType(tp.getName()) == null) {
                    if (!types.containsKey(tp.getName())) {
                        customTypeHits++;
                    }
                    types.put(tp.getName(), tp);
                }

                // Check if the entry should be written.
                boolean write = true;

                if (checkSearch && !nonZeroField(be, BibtexFields.SEARCH)) {
                    write = false;
                    skippedSearch++;
                }

                if (checkGroup && !nonZeroField(be, BibtexFields.GROUPSEARCH)) {
                    write = false;
                    skippedGroup++;
                }

                if (write) {
                    bibtexEntryWriter.write(be, fw);
                    fw.write(Globals.NEWLINE);
                    writtenEntries++;
                }
            }
            logPerf("saveDatabase write entries", writeStart,
                    "considered=" + consideredEntries
                    + ", written=" + writtenEntries
                    + ", skippedSearch=" + skippedSearch
                    + ", skippedGroup=" + skippedGroup
                    + ", customTypes=" + customTypeHits);

            // Write meta data.
            long metaStart = perfStart();
            if (metaData != null) {
                metaData.writeMetaData(fw);
            }
            logPerf("saveDatabase writeMetaData", metaStart, "hasMetaData=" + (metaData != null));

            // Write type definitions, if any:
            long typeStart = perfStart();
            int customTypesWritten = 0;
            if (types.size() > 0) {
                for (String s : types.keySet()) {
                    BibtexEntryType type = types.get(s);
                    if (type instanceof CustomEntryType) {
                        CustomEntryType tp = (CustomEntryType) type;
                        tp.save(fw);
                        fw.write(Globals.NEWLINE);
                        customTypesWritten++;
                    }
                }

            }
            logPerf("saveDatabase write custom type definitions", typeStart,
                    "types=" + types.size() + ", written=" + customTypesWritten);

            long closeStart = perfStart();
            fw.close();
            logPerf("saveDatabase writer.close", closeStart,
                    "tmpBytes=" + fileSize(session.getTemporaryFile()));
        } catch (Throwable ex) {
            ex.printStackTrace();
            long cancelStart = perfStart();
            session.cancel();
            logPerf("saveDatabase cancel after exception", cancelStart,
                    "tmpBytes=" + fileSize(session.getTemporaryFile()));
            // repairAfterError(file, backup, INIT_OK);
            throw new SaveException(ex.getMessage(), exceptionCause);
        }

        logPerf("saveDatabase total", totalStart,
                "entries=" + entryCount
                + ", tmpBytes=" + fileSize(session.getTemporaryFile())
                + ", " + fileDetails(file));
        return session;

    }

    private static class SaveSettings {

        public final String pri, sec, ter;
        public final boolean priD, secD, terD;

        public SaveSettings(boolean isSaveOperation, MetaData metaData) {
            /* three options:
             * 1. original order (saveInOriginalOrder) -- not hit here as SaveSettings is not called in that case
             * 2. current table sort order
             * 3. ordered by specified order
             */

            List<String> storedSaveOrderConfig = null;
            if (isSaveOperation) {
                storedSaveOrderConfig = metaData.getData(net.sf.jabref.gui.DatabasePropertiesDialog.SAVE_ORDER_CONFIG);
            }

            // This case should never be hit as SaveSettings() is never called if InOriginalOrder is true
            assert (storedSaveOrderConfig == null) && isSaveOperation && !Globals.prefs.getBoolean(JabRefPreferences.SAVE_IN_ORIGINAL_ORDER);
            assert (storedSaveOrderConfig == null) && !isSaveOperation && !Globals.prefs.getBoolean(JabRefPreferences.EXPORT_IN_ORIGINAL_ORDER);

            if (storedSaveOrderConfig != null) {
                // follow the metaData
                SaveOrderConfig saveOrderConfig = new SaveOrderConfig(storedSaveOrderConfig);
                assert (!saveOrderConfig.saveInOriginalOrder);
                assert (saveOrderConfig.saveInSpecifiedOrder);
                pri = saveOrderConfig.sortCriteria[0].field;
                sec = saveOrderConfig.sortCriteria[1].field;
                ter = saveOrderConfig.sortCriteria[2].field;
                priD = saveOrderConfig.sortCriteria[0].descending;
                secD = saveOrderConfig.sortCriteria[1].descending;
                terD = saveOrderConfig.sortCriteria[2].descending;
            } else if (isSaveOperation && Globals.prefs.getBoolean(JabRefPreferences.SAVE_IN_SPECIFIED_ORDER)) {
                pri = Globals.prefs.get(JabRefPreferences.SAVE_PRIMARY_SORT_FIELD);
                sec = Globals.prefs.get(JabRefPreferences.SAVE_SECONDARY_SORT_FIELD);
                ter = Globals.prefs.get(JabRefPreferences.SAVE_TERTIARY_SORT_FIELD);
                priD = Globals.prefs.getBoolean(JabRefPreferences.SAVE_PRIMARY_SORT_DESCENDING);
                secD = Globals.prefs.getBoolean(JabRefPreferences.SAVE_SECONDARY_SORT_DESCENDING);
                terD = Globals.prefs.getBoolean(JabRefPreferences.SAVE_TERTIARY_SORT_DESCENDING);
            } else if (!isSaveOperation && Globals.prefs.getBoolean(JabRefPreferences.EXPORT_IN_SPECIFIED_ORDER)) {
                pri = Globals.prefs.get(JabRefPreferences.EXPORT_PRIMARY_SORT_FIELD);
                sec = Globals.prefs.get(JabRefPreferences.EXPORT_SECONDARY_SORT_FIELD);
                ter = Globals.prefs.get(JabRefPreferences.EXPORT_TERTIARY_SORT_FIELD);
                priD = Globals.prefs.getBoolean(JabRefPreferences.EXPORT_PRIMARY_SORT_DESCENDING);
                secD = Globals.prefs.getBoolean(JabRefPreferences.EXPORT_SECONDARY_SORT_DESCENDING);
                terD = Globals.prefs.getBoolean(JabRefPreferences.EXPORT_TERTIARY_SORT_DESCENDING);
            } else {
                // The setting is to save according to the current table order.
                pri = Globals.prefs.get(JabRefPreferences.PRIMARY_SORT_FIELD);
                sec = Globals.prefs.get(JabRefPreferences.SECONDARY_SORT_FIELD);
                ter = Globals.prefs.get(JabRefPreferences.TERTIARY_SORT_FIELD);
                priD = Globals.prefs.getBoolean(JabRefPreferences.PRIMARY_SORT_DESCENDING);
                secD = Globals.prefs.getBoolean(JabRefPreferences.SECONDARY_SORT_DESCENDING);
                terD = Globals.prefs.getBoolean(JabRefPreferences.TERTIARY_SORT_DESCENDING);
            }
        }
    }

    private static List<Comparator<BibtexEntry>> getSaveComparators(boolean isSaveOperation, MetaData metaData) {
        SaveSettings saveSettings = new SaveSettings(isSaveOperation, metaData);

        List<Comparator<BibtexEntry>> comparators = new ArrayList<Comparator<BibtexEntry>>();
        if (isSaveOperation) {
            comparators.add(new CrossRefEntryComparator());
        }
        comparators.add(new FieldComparator(saveSettings.pri, saveSettings.priD));
        comparators.add(new FieldComparator(saveSettings.sec, saveSettings.secD));
        comparators.add(new FieldComparator(saveSettings.ter, saveSettings.terD));
        comparators.add(new FieldComparator(BibtexFields.KEY_FIELD));

        return comparators;
    }

    /**
     * Saves the database to file, including only the entries included in the
     * supplied input array bes.
     *
     * @return A List containing warnings, if any.
     */
    public static SaveSession savePartOfDatabase(BibtexDatabase database, MetaData metaData,
            File file, JabRefPreferences prefs, BibtexEntry[] bes, String encoding, DatabaseSaveType saveType) throws SaveException {

        long totalStart = perfStart();
        TreeMap<String, BibtexEntryType> types = new TreeMap<String, BibtexEntryType>(); // Map
        // to
        // collect
        // entry
        // type
        // definitions
        // that we must save along with entries using them.

        BibtexEntry be = null;
        boolean backup = prefs.getBoolean("backup");
        int inputEntries = (bes == null) ? 0 : bes.length;
        System.out.println("[FileActions timer] savePartOfDatabase start entries=" + inputEntries
                + " saveType=" + saveType
                + " backup=" + backup
                + " encoding=" + encoding
                + " " + fileDetails(file)
                + " thread=" + Thread.currentThread().getName()
                + " edt=" + SwingUtilities.isEventDispatchThread());

        SaveSession session;
        long sessionStart = perfStart();
        try {
            session = new SaveSession(file, encoding, backup);
        } catch (IOException e) {
            logPerf("savePartOfDatabase new SaveSession failed", sessionStart, "encoding=" + encoding + " " + fileDetails(file));
            throw new SaveException(e.getMessage());
        }
        logPerf("savePartOfDatabase new SaveSession", sessionStart, "tmpBytes=" + fileSize(session.getTemporaryFile()));

        try {

            // Define our data stream.
            long writerStart = perfStart();
            VerifyingWriter fw = session.getWriter();
            logPerf("savePartOfDatabase getWriter", writerStart);

            if (saveType != DatabaseSaveType.PLAIN_BIBTEX) {
                // Write signature.
                long headerStart = perfStart();
                writeBibFileHeader(fw, encoding);
                logPerf("savePartOfDatabase writeBibFileHeader", headerStart);
            }

            // Write preamble if there is one.
            String preamble = database.getPreamble();
            long preambleStart = perfStart();
            writePreamble(fw, preamble);
            logPerf("savePartOfDatabase writePreamble", preambleStart, "hasPreamble=" + (preamble != null));

            // Write strings if there are any.
            long stringsStart = perfStart();
            writeStrings(fw, database);
            logPerf("savePartOfDatabase writeStrings", stringsStart);

            // Write database entries. Take care, using CrossRefEntry-
            // Comparator, that referred entries occur after referring
            // ones. Apart from crossref requirements, entries will be
            // sorted as they appear on the screen.
            long comparatorStart = perfStart();
            List<Comparator<BibtexEntry>> comparators = getSaveComparators(true, metaData);
            logPerf("savePartOfDatabase getSaveComparators", comparatorStart, "comparators=" + comparators.size());

            // Use glazed lists to get a sorted view of the entries:
            long copyStart = perfStart();
            List<BibtexEntry> sorter = new ArrayList<BibtexEntry>(bes.length);
            Collections.addAll(sorter, bes);
            logPerf("savePartOfDatabase copy selected entries", copyStart, "entries=" + sorter.size());

            long sortStart = perfStart();
            Collections.sort(sorter, new FieldComparatorStack<BibtexEntry>(comparators));
            logPerf("savePartOfDatabase sort selected entries", sortStart, "entries=" + sorter.size());

            long entryWriterStart = perfStart();
            BibtexEntryWriter bibtexEntryWriter = new BibtexEntryWriter(new LatexFieldFormatter(), true);
            logPerf("savePartOfDatabase new BibtexEntryWriter", entryWriterStart);

            long writeStart = perfStart();
            int writtenEntries = 0;
            int customTypeHits = 0;
            for (BibtexEntry aSorter : sorter) {
                be = (aSorter);

                // Check if we must write the type definition for this
                // entry, as well. Our criterion is that all non-standard
                // types (*not* customized standard types) must be written.
                BibtexEntryType tp = be.getType();
                if (BibtexEntryType.getStandardType(tp.getName()) == null) {
                    if (!types.containsKey(tp.getName())) {
                        customTypeHits++;
                    }
                    types.put(tp.getName(), tp);
                }

                bibtexEntryWriter.write(be, fw);
                fw.write(Globals.NEWLINE);
                writtenEntries++;
            }
            logPerf("savePartOfDatabase write entries", writeStart,
                    "written=" + writtenEntries + ", customTypes=" + customTypeHits);

            // Write meta data.
            long metaStart = perfStart();
            if (saveType != DatabaseSaveType.PLAIN_BIBTEX && metaData != null) {
                metaData.writeMetaData(fw);
            }
            logPerf("savePartOfDatabase writeMetaData", metaStart,
                    "writeMetaData=" + (saveType != DatabaseSaveType.PLAIN_BIBTEX && metaData != null));

            // Write type definitions, if any:
            long typeStart = perfStart();
            int customTypesWritten = 0;
            if (types.size() > 0) {
                for (String s : types.keySet()) {
                    CustomEntryType tp = (CustomEntryType) types.get(s);
                    tp.save(fw);
                    fw.write(Globals.NEWLINE);
                    customTypesWritten++;
                }

            }
            logPerf("savePartOfDatabase write custom type definitions", typeStart,
                    "types=" + types.size() + ", written=" + customTypesWritten);

            long closeStart = perfStart();
            fw.close();
            logPerf("savePartOfDatabase writer.close", closeStart,
                    "tmpBytes=" + fileSize(session.getTemporaryFile()));
        } catch (Throwable ex) {
            long cancelStart = perfStart();
            session.cancel();
            logPerf("savePartOfDatabase cancel after exception", cancelStart,
                    "tmpBytes=" + fileSize(session.getTemporaryFile()));
            //repairAfterError(file, backup, status);
            throw new SaveException(ex.getMessage(), be);
        }

        logPerf("savePartOfDatabase total", totalStart,
                "entries=" + inputEntries
                + ", tmpBytes=" + fileSize(session.getTemporaryFile())
                + ", " + fileDetails(file));
        return session;

    }

    /**
     * This method attempts to get a Reader for the file path given, either by
     * loading it as a resource (from within jar), or as a normal file. If
     * unsuccessful (e.g. file not found), an IOException is thrown.
     */
    public static Reader getReader(String name) throws IOException {
        Reader reader = null;
        // Try loading as a resource first. This works for files inside the jar:
        URL reso = Globals.class.getResource(name);

        // If that didn't work, try loading as a normal file URL:
        if (reso != null) {
            try {
                reader = new InputStreamReader(reso.openStream());
            } catch (FileNotFoundException ex) {
                throw new IOException(Globals.lang("Could not find layout file") + ": '" + name + "'.");
            }
        } else {
            File f = new File(name);
            try {
                reader = new FileReader(f);
            } catch (FileNotFoundException ex) {
                throw new IOException(Globals.lang("Could not find layout file") + ": '" + name + "'.");
            }
        }

        return reader;
    }

    /*
     * We have begun to use getSortedEntries() for both database save operations
     * and non-database save operations.  In a non-database save operation
     * (such as the exportDatabase call), we do not wish to use the
     * global preference of saving in standard order.
     */
    @SuppressWarnings("unchecked")
    public static List<BibtexEntry> getSortedEntries(BibtexDatabase database, MetaData metaData, Set<String> keySet, boolean isSaveOperation) {
        long totalStart = perfStart();
        long orderStart = perfStart();
        boolean inOriginalOrder;
        if (isSaveOperation) {
            List<String> storedSaveOrderConfig = metaData.getData(net.sf.jabref.gui.DatabasePropertiesDialog.SAVE_ORDER_CONFIG);
            if (storedSaveOrderConfig == null) {
                inOriginalOrder = Globals.prefs.getBoolean("saveInOriginalOrder");
            } else {
                SaveOrderConfig saveOrderConfig = new SaveOrderConfig(storedSaveOrderConfig);
                inOriginalOrder = saveOrderConfig.saveInOriginalOrder;
            }
        } else {
            inOriginalOrder = Globals.prefs.getBoolean("exportInOriginalOrder");
        }
        logPerf("getSortedEntries determine order", orderStart,
                "isSaveOperation=" + isSaveOperation + ", inOriginalOrder=" + inOriginalOrder);

        long comparatorStart = perfStart();
        List<Comparator<BibtexEntry>> comparators;
        if (inOriginalOrder) {
            // Sort entries based on their creation order, utilizing the fact
            // that IDs used for entries are increasing, sortable numbers.
            comparators = new ArrayList<Comparator<BibtexEntry>>();
            comparators.add(new CrossRefEntryComparator());
            comparators.add(new IdComparator());
        } else {
            comparators = getSaveComparators(isSaveOperation, metaData);
        }
        FieldComparatorStack<BibtexEntry> comparatorStack = new FieldComparatorStack<BibtexEntry>(comparators);
        logPerf("getSortedEntries build comparators", comparatorStart,
                "comparators=" + comparators.size());

        long keySetStart = perfStart();
        if (keySet == null) {
            keySet = database.getKeySet();
        }
        int keyCount = (keySet == null) ? 0 : keySet.size();
        logPerf("getSortedEntries getKeySet", keySetStart, "keys=" + keyCount);

        long buildListStart = perfStart();
        List<BibtexEntry> sorter = new ArrayList<BibtexEntry>(keyCount);
        int nullEntries = 0;

        if (keySet != null) {
            Iterator<String> i = keySet.iterator();

            for (; i.hasNext();) {
                BibtexEntry entry = database.getEntryById((i.next()));
                if (entry == null) {
                    nullEntries++;
                }
                sorter.add(entry);
            }
        }
        logPerf("getSortedEntries build entry list", buildListStart,
                "keys=" + keyCount + ", entries=" + sorter.size() + ", nullEntries=" + nullEntries);

        long sortStart = perfStart();
        Collections.sort(sorter, comparatorStack);
        logPerf("getSortedEntries Collections.sort", sortStart,
                "entries=" + sorter.size() + ", comparators=" + comparators.size());

        logPerf("getSortedEntries total", totalStart,
                "entries=" + sorter.size() + ", isSaveOperation=" + isSaveOperation + ", inOriginalOrder=" + inOriginalOrder);
        return sorter;
    }

    /**
     * @return true iff the entry has a nonzero value in its field.
     */
    private static boolean nonZeroField(BibtexEntry be, String field) {
        String o = (be.getField(field));

        return ((o != null) && !o.equals("0"));
    }
}

///////////////////////////////////////////////////////////////////////////////
//  END OF FILE.
///////////////////////////////////////////////////////////////////////////////
