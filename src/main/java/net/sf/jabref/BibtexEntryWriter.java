package net.sf.jabref;

import net.sf.jabref.export.FieldFormatter;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

public class BibtexEntryWriter {

    private static final boolean PERF_TIMERS = false;
    private static final int PERF_SUMMARY_EVERY_ENTRIES = 5000;

    /**
     * Display name map for entry field names.
     */
    private static final Map<String, String> tagDisplayNameMap = new HashMap<>();

    static {
        // The field name display map.
        tagDisplayNameMap.put("bibtexkey", "BibTeXKey");
        tagDisplayNameMap.put("howpublished", "HowPublished");
        tagDisplayNameMap.put("lastchecked", "LastChecked");
        tagDisplayNameMap.put("isbn", "ISBN");
        tagDisplayNameMap.put("issn", "ISSN");
        tagDisplayNameMap.put("UNKNOWN", "UNKNOWN");
    }

    /**
     * The maximum length of a field name to properly make the alignment of the
     * equal sign.
     */
    private static final int MaxFieldLength;

    static {
        // Looking for the longest field name.
        // XXX JK: Look for all used field names not only defined once, since
        //         there may be some unofficial field name used.
        int max = 0;
        for (BibtexEntryType t : BibtexEntryType.ALL_TYPES.values()) {
            if (t.getRequiredFields() != null) {
                for (String field : t.getRequiredFields()) {
                    max = Math.max(max, field.length());
                }
            }
            if (t.getOptionalFields() != null) {
                for (String field : t.getOptionalFields()) {
                    max = Math.max(max, field.length());
                }
            }
        }
        MaxFieldLength = max;
    }

    private final FieldFormatter fieldFormatter;
    private final boolean write;
    private final boolean writeFieldCameCaseName = Globals.prefs.getBoolean(JabRefPreferences.WRITEFIELD_CAMELCASENAME);
    private final boolean writeFieldAddSpaces = Globals.prefs.getBoolean(JabRefPreferences.WRITEFIELD_ADDSPACES);
    private final boolean includeEmptyFields = Globals.prefs.getBoolean("includeEmptyFields");
    private final int writeFieldSortStype = Globals.prefs.getInt(JabRefPreferences.WRITEFIELD_SORTSTYLE);

    private final Map<String, String> fieldDisplayNameCache = new HashMap<>();
    private final Map<BibtexEntryType, String[]> sortedRequiredFieldsCache = new HashMap<>();
    private final Map<BibtexEntryType, String[]> sortedOptionalFieldsCache = new HashMap<>();

    private long entriesWritten;
    private long writeEntryNanos;
    private long writeFieldAttempts;
    private long fieldsWritten;
    private long fieldsSkipped;
    private long formatNanos;
    private long outputWriteNanos;
    private long remainingFieldBuildNanos;
    private long charsFormatted;
    private long maxEntryNanos;

    public BibtexEntryWriter(FieldFormatter fieldFormatter, boolean write) {
        this.fieldFormatter = fieldFormatter;
        this.write = write;
    }

    public void write(BibtexEntry entry, Writer out) throws IOException {
        long start = System.nanoTime();
        switch (writeFieldSortStype) {
            case 0:
                writeSorted(entry, out);
                break;
            case 1:
                writeUnsorted(entry, out);
                break;
            case 2:
                writeUserDefinedOrder(entry, out);
                break;
            default:
                writeSorted(entry, out);
                break;
        }
        long elapsed = System.nanoTime() - start;
        entriesWritten++;
        writeEntryNanos += elapsed;
        if (elapsed > maxEntryNanos) {
            maxEntryNanos = elapsed;
        }
        if (PERF_TIMERS && (entriesWritten % PERF_SUMMARY_EVERY_ENTRIES == 0)) {
            printPerfSummary("periodic");
        }
    }

    /**
     * new style ver>=2.10, sort the field for requiredFields, optionalFields
     * and other fields separately
     *
     * @param entry
     * @param out
     * @throws IOException
     */
    private void writeSorted(BibtexEntry entry, Writer out) throws IOException {
        // Write header with type and bibtex-key.
        String str = Util.shaveString(entry.getField(BibtexFields.KEY_FIELD));
        out.write("@" + entry.getType().getName() + "{" + ((str == null) ? "" : str) + "," + Globals.NEWLINE);

        Set<String> written = new HashSet<>();
        written.add(BibtexFields.KEY_FIELD);

        // Write required fields first. Thereby, write the title field first.
        boolean hasWritten = writeField(entry, out, "title", false, false);
        written.add("title");

        String[] s = getCachedSortedRequiredFields(entry);
        if (s != null) {
            for (String value : s) {
                if (!written.contains(value)) { // If field appears both in req. and opt. don't repeat.
                    hasWritten = hasWritten | writeField(entry, out, value, hasWritten, false);
                    written.add(value);
                }
            }
        }

        // Then optional fields.
        s = getCachedSortedOptionalFields(entry);
        boolean first = true;
        boolean previous = false;
        if (s != null) {
            for (String value : s) {
                if (!written.contains(value)) { // If field appears both in req. and opt. don't repeat.
                    hasWritten = hasWritten | writeField(entry, out, value, hasWritten, hasWritten && first);
                    written.add(value);
                    first = false;
                    previous = true;
                }
            }
        }

        // Then write remaining fields in alphabetic order.
        long remainingStart = System.nanoTime();
        TreeSet<String> remainingFields = new TreeSet<>();
        for (String key : entry.getAllFields()) {
            boolean writeIt = (write ? BibtexFields.isWriteableField(key)
                    : BibtexFields.isDisplayableField(key));
            if (!written.contains(key) && writeIt) {
                remainingFields.add(key);
            }
        }
        remainingFieldBuildNanos += System.nanoTime() - remainingStart;

        first = previous;
        for (String field : remainingFields) {
            hasWritten = hasWritten | writeField(entry, out, field, hasWritten, hasWritten && first);
            first = false;
        }

        // Finally, end the entry.
        out.write((hasWritten ? Globals.NEWLINE : "") + "}" + Globals.NEWLINE);
    }

    /**
     * old style ver<=2.9.2, write fields in the order of requiredFields,
     * optionalFields and other fields, but does not sort the fields.
     *
     * @param entry
     * @param out
     * @throws IOException
     */
    private void writeUnsorted(BibtexEntry entry, Writer out) throws IOException {
        // Write header with type and bibtex-key.
        String str = Util.shaveString(entry.getField(BibtexFields.KEY_FIELD));
        out.write("@" + entry.getType().getName().toUpperCase(Locale.US) + "{" + ((str == null) ? "" : str) + "," + Globals.NEWLINE);

        Set<String> written = new HashSet<>();
        written.add(BibtexFields.KEY_FIELD);
        boolean hasWritten = false;

        // Write required fields first.
        String[] s = entry.getRequiredFields();
        if (s != null) {
            for (String value : s) {
                hasWritten = hasWritten | writeField(entry, out, value, hasWritten, false);
                written.add(value);
            }
        }

        // Then optional fields.
        s = entry.getOptionalFields();
        if (s != null) {
            for (String value : s) {
                if (!written.contains(value)) { // If field appears both in req. and opt. don't repeat.
                    hasWritten = hasWritten | writeField(entry, out, value, hasWritten, false);
                    written.add(value);
                }
            }
        }

        // Then write remaining fields in alphabetic order.
        long remainingStart = System.nanoTime();
        TreeSet<String> remainingFields = new TreeSet<>();
        for (String key : entry.getAllFields()) {
            boolean writeIt = (write ? BibtexFields.isWriteableField(key)
                    : BibtexFields.isDisplayableField(key));
            if (!written.contains(key) && writeIt) {
                remainingFields.add(key);
            }
        }
        remainingFieldBuildNanos += System.nanoTime() - remainingStart;

        for (String field : remainingFields) {
            hasWritten = hasWritten | writeField(entry, out, field, hasWritten, false);
        }

        // Finally, end the entry.
        out.write((hasWritten ? Globals.NEWLINE : "") + "}" + Globals.NEWLINE);
    }

    private void writeUserDefinedOrder(BibtexEntry entry, Writer out) throws IOException {
        // Write header with type and bibtex-key.
        String str = Util.shaveString(entry.getField(BibtexFields.KEY_FIELD));
        out.write("@" + entry.getType().getName() + "{" + ((str == null) ? "" : str) + "," + Globals.NEWLINE);

        Set<String> written = new HashSet<>();
        written.add(BibtexFields.KEY_FIELD);
        boolean hasWritten = false;

        // Write user defined fields first.
        String[] s = entry.getUserDefinedFields();
        if (s != null) {
            // do not sort, write as it is.
            for (String value : s) {
                if (!written.contains(value)) { // If field appears both in req. and opt. don't repeat.
                    hasWritten = hasWritten | writeField(entry, out, value, hasWritten, false);
                    written.add(value);
                }
            }
        }

        // Then write remaining fields in alphabetic order.
        boolean first = true;
        boolean previous = false;

        long remainingStart = System.nanoTime();
        TreeSet<String> remainingFields = new TreeSet<>();
        for (String key : entry.getAllFields()) {
            boolean writeIt = (write ? BibtexFields.isWriteableField(key)
                    : BibtexFields.isDisplayableField(key));
            if (!written.contains(key) && writeIt) {
                remainingFields.add(key);
            }
        }
        remainingFieldBuildNanos += System.nanoTime() - remainingStart;

        first = previous;
        for (String field : remainingFields) {
            hasWritten = hasWritten | writeField(entry, out, field, hasWritten, hasWritten && first);
            first = false;
        }

        // Finally, end the entry.
        out.write((hasWritten ? Globals.NEWLINE : "") + "}" + Globals.NEWLINE);
    }

    /**
     * Write a single field, if it has any content.
     *
     * @param entry the entry to write
     * @param out the target of the write
     * @param name The field name
     * @param isNotFirst Indicates whether this is the first field written for
     * this entry - if not, start by writing a comma and newline @return true if
     * this field was written, false if it was skipped because it was not set
     * @throws IOException In case of an IO error
     */
    private boolean writeField(BibtexEntry entry, Writer out, String name, boolean isNotFirst, boolean isNextGroup) throws IOException {
        writeFieldAttempts++;
        String o = entry.getField(name);
        if (o != null || includeEmptyFields) {
            String formatted;
            try {
                long formatStart = System.nanoTime();
                formatted = fieldFormatter.format(o, name);
                formatNanos += System.nanoTime() - formatStart;
                if (formatted != null) {
                    charsFormatted += formatted.length();
                }
            } catch (Throwable ex) {
                throw new IOException(Globals.lang("Error in field") + " '" + name + "': " + ex.getMessage());
            }

            StringBuilder fieldOutput = new StringBuilder((formatted == null ? 0 : formatted.length()) + 32);
            if (isNotFirst) {
                fieldOutput.append(',').append(Globals.NEWLINE);
            }
            if (isNextGroup) {
                fieldOutput.append(Globals.NEWLINE);
            }
            fieldOutput.append("  ").append(getFieldDisplayName(name)).append(" = ");
            fieldOutput.append(formatted);

            long writeStart = System.nanoTime();
            out.write(fieldOutput.toString());
            outputWriteNanos += System.nanoTime() - writeStart;

            fieldsWritten++;
            return true;
        } else {
            fieldsSkipped++;
            return false;
        }
    }

    /**
     * Get display version of a entry field.
     * <p/>
     * BibTeX is case-insensitive therefore there is no difference between:
     * howpublished, HOWPUBLISHED, HowPublished, etc. Since the camel case
     * version is the most easy to read this should be the one written in the
     * *.bib file. Since there is no way how do detect multi-word strings by
     * default the first character will be made uppercase. In other characters
     * case needs to be changed the {@link #tagDisplayNameMap} will be used.
     *
     * @param field The name of the field.
     * @return The display version of the field name.
     */
    private String getFieldDisplayName(String field) {
        String cacheKey = field;
        String cached = fieldDisplayNameCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String effectiveField = field;
        if (effectiveField.length() == 0) {
            // hard coded "UNKNOWN" is assigned to a field without any name
            effectiveField = "UNKNOWN";
        }

        String suffix = "";
        if (writeFieldAddSpaces) {
            suffix = repeatSpaces(Math.max(0, MaxFieldLength - effectiveField.length()));
        }

        String res;
        if (writeFieldCameCaseName) {
            String lookup = effectiveField.toLowerCase();
            if (tagDisplayNameMap.containsKey(lookup)) {
                res = tagDisplayNameMap.get(lookup) + suffix;
            } else {
                res = (effectiveField.charAt(0) + "").toUpperCase() + effectiveField.substring(1) + suffix;
            }
        } else {
            res = effectiveField + suffix;
        }
        fieldDisplayNameCache.put(cacheKey, res);
        return res;
    }

    private String[] getCachedSortedRequiredFields(BibtexEntry entry) {
        BibtexEntryType type = entry.getType();
        if (!sortedRequiredFieldsCache.containsKey(type)) {
            sortedRequiredFieldsCache.put(type, sortedCopy(entry.getRequiredFields()));
        }
        return sortedRequiredFieldsCache.get(type);
    }

    private String[] getCachedSortedOptionalFields(BibtexEntry entry) {
        BibtexEntryType type = entry.getType();
        if (!sortedOptionalFieldsCache.containsKey(type)) {
            sortedOptionalFieldsCache.put(type, sortedCopy(entry.getOptionalFields()));
        }
        return sortedOptionalFieldsCache.get(type);
    }

    private static String[] sortedCopy(String[] fields) {
        if (fields == null) {
            return null;
        }
        String[] copy = new String[fields.length];
        System.arraycopy(fields, 0, copy, 0, fields.length);
        Arrays.sort(copy);
        return copy;
    }

    private static String repeatSpaces(int count) {
        if (count <= 0) {
            return "";
        }
        char[] chars = new char[count];
        Arrays.fill(chars, ' ');
        return new String(chars);
    }

    private void printPerfSummary(String reason) {
        System.out.println("[BibtexEntryWriter timer] " + reason
                + " entries=" + entriesWritten
                + ", fieldsWritten=" + fieldsWritten
                + ", fieldAttempts=" + writeFieldAttempts
                + ", fieldsSkipped=" + fieldsSkipped
                + ", charsFormatted=" + charsFormatted
                + ", entryTotalMs=" + nanosToMs(writeEntryNanos)
                + ", maxEntryMs=" + nanosToMs(maxEntryNanos)
                + ", formatMs=" + nanosToMs(formatNanos)
                + ", outputWriteMs=" + nanosToMs(outputWriteNanos)
                + ", remainingFieldBuildMs=" + nanosToMs(remainingFieldBuildNanos)
                + ", displayNameCache=" + fieldDisplayNameCache.size()
                + ", reqCache=" + sortedRequiredFieldsCache.size()
                + ", optCache=" + sortedOptionalFieldsCache.size()
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
