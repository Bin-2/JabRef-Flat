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
package net.sf.jabref;

import net.sf.jabref.gui.MainTableFormat;

import java.text.CollationKey;
import java.text.Collator;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A comparator for BibtexEntry fields
 *
 * Initial Version:
 *
 * @author alver
 * @version Date: Oct 13, 2005 Time: 10:10:04 PM To
 *
 * Current Version:
 *
 * @author $Author$
 * @version $Revision$ ($Date$)
 *
 * TODO: Testcases
 */
public class FieldComparator implements Comparator<BibtexEntry> {

    private static final boolean PERF_TIMERS = false;
    private static final long PERF_LOG_EVERY_COMPARISONS = 100000L;

    private static final Collator collator;

    static {
        Collator tmp;
        try {
            tmp = new RuleBasedCollator(
                    ((RuleBasedCollator) Collator.getInstance()).getRules()
                            .replaceAll("<'\u005f'", "<' '<'\u005f'"));
        } catch (ParseException e) {
            tmp = Collator.getInstance();
        }
        collator = tmp;
    }

    private final String[] field;
    private final String fieldName;

    boolean isNameField, isTypeHeader, isYearField, isMonthField, isNumeric;

    int multiplier;

    /**
     * Timestamp values are machine-generated date/time strings. Locale collation
     * and lower-case allocation are unnecessary for them, and they dominate the
     * initial table sort on large databases.
     */
    private final boolean useFastTimestampCompare;

    /**
     * These fields are identifiers or URLs, not human-language text. Locale
     * collation is unnecessarily expensive and does not add useful ordering.
     */
    private final boolean useFastStringCompare;

    /**
     * Cache normalized sort values for this comparator instance. This is safe for
     * normal table sorting because the raw field value is checked on every lookup;
     * if an entry changes, the cached normalized value is rebuilt.
     */
    private final Map<BibtexEntry, CachedSortValue> sortValueCache = new IdentityHashMap<BibtexEntry, CachedSortValue>();

    private long compareCount = 0;
    private long getFieldNanos = 0;
    private long normalizeNanos = 0;
    private long compareNanos = 0;
    private long totalNanos = 0;
    private long nullComparisons = 0;
    private long integerComparisons = 0;
    private long collatorComparisons = 0;
    private long fastTimestampComparisons = 0;
    private long fastStringComparisons = 0;
    private long nameNormalizations = 0;
    private long yearNormalizations = 0;
    private long monthNormalizations = 0;
    private long numericNormalizations = 0;
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long cacheRefreshes = 0;
    private long collationKeyCreations = 0;

    private static final class CachedSortValue {
        private Object rawValue;
        private Object normalizedValue;
        private boolean numericCandidate;
        private boolean numericParsed;
        private int numericValue;
        private CollationKey collationKey;
    }

    public FieldComparator(String field) {
        this(field, false);
    }

    public FieldComparator(String field, boolean reversed) {
        this.fieldName = field;
        this.field = field.split(MainTableFormat.COL_DEFINITION_FIELD_SEPARATOR);
        multiplier = reversed ? -1 : 1;
        isTypeHeader = this.field[0].equals(GUIGlobals.TYPE_HEADER);
        isNameField = (this.field[0].equals("author")
                || this.field[0].equals("editor"));
        isYearField = this.field[0].equals("year");
        isMonthField = this.field[0].equals("month");
        isNumeric = BibtexFields.isNumeric(this.field[0]);
        useFastTimestampCompare = (this.field.length == 1) && this.field[0].equals("timestamp");
        useFastStringCompare = useFastTimestampCompare || isFastStringField(this.field);
    }

    @Override
    public int compare(BibtexEntry e1, BibtexEntry e2) {
        long start = PERF_TIMERS ? System.nanoTime() : 0L;
        try {
            return compareInternal(e1, e2);
        } finally {
            if (PERF_TIMERS) {
                totalNanos += System.nanoTime() - start;
                compareCount++;
                maybeLogPerf(false);
            }
        }
    }

    private int compareInternal(BibtexEntry e1, BibtexEntry e2) {
        CachedSortValue v1 = getCachedSortValue(e1);
        CachedSortValue v2 = getCachedSortValue(e2);

        /*
         * [ 1598777 ] Month sorting
         *
         * http://sourceforge.net/tracker/index.php?func=detail&aid=1598777&group_id=92314&atid=600306
         */
        int localMultiplier = multiplier;
        if (isMonthField) {
            localMultiplier = -localMultiplier;
        }

        // Catch all cases involving null:
        if (v1.rawValue == null) {
            if (PERF_TIMERS) {
                nullComparisons++;
            }
            return v2.rawValue == null ? 0 : localMultiplier;
        }

        if (v2.rawValue == null) {
            if (PERF_TIMERS) {
                nullComparisons++;
            }
            return -localMultiplier;
        }

        long compareStart = PERF_TIMERS ? System.nanoTime() : 0L;
        int result;

        if (isNumeric) {
            result = compareNumericAware(v1, v2);
        } else if ((v1.normalizedValue instanceof Integer) && (v2.normalizedValue instanceof Integer)) {
            result = ((Integer) v1.normalizedValue).compareTo((Integer) v2.normalizedValue);
            if (PERF_TIMERS) {
                integerComparisons++;
            }
        } else if (useFastTimestampCompare) {
            result = String.CASE_INSENSITIVE_ORDER.compare(
                    String.valueOf(v1.normalizedValue),
                    String.valueOf(v2.normalizedValue));
            if (PERF_TIMERS) {
                fastTimestampComparisons++;
            }
        } else if (useFastStringCompare) {
            result = String.CASE_INSENSITIVE_ORDER.compare(
                    String.valueOf(v1.normalizedValue),
                    String.valueOf(v2.normalizedValue));
            if (PERF_TIMERS) {
                fastStringComparisons++;
            }
        } else {
            result = compareUsingCollationKeys(v1, v2);
            if (PERF_TIMERS) {
                collatorComparisons++;
            }
        }

        if (PERF_TIMERS) {
            compareNanos += System.nanoTime() - compareStart;
        }

        return result * localMultiplier;
    }

    private int compareNumericAware(CachedSortValue v1, CachedSortValue v2) {
        if (v1.numericParsed && v2.numericParsed) {
            if (PERF_TIMERS) {
                integerComparisons++;
            }
            return Integer.valueOf(v1.numericValue).compareTo(Integer.valueOf(v2.numericValue));
        }
        if (v1.numericParsed) {
            if (PERF_TIMERS) {
                integerComparisons++;
            }
            return -1;
        }
        if (v2.numericParsed) {
            if (PERF_TIMERS) {
                integerComparisons++;
            }
            return 1;
        }

        if (useFastStringCompare) {
            if (PERF_TIMERS) {
                fastStringComparisons++;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(
                    String.valueOf(v1.normalizedValue),
                    String.valueOf(v2.normalizedValue));
        }

        if (PERF_TIMERS) {
            collatorComparisons++;
        }
        return compareUsingCollationKeys(v1, v2);
    }

    private int compareUsingCollationKeys(CachedSortValue v1, CachedSortValue v2) {
        CollationKey key1 = getOrCreateCollationKey(v1);
        CollationKey key2 = getOrCreateCollationKey(v2);
        return key1.compareTo(key2);
    }

    private CollationKey getOrCreateCollationKey(CachedSortValue value) {
        if (value.collationKey == null) {
            synchronized (collator) {
                value.collationKey = collator.getCollationKey(String.valueOf(value.normalizedValue));
            }
            if (PERF_TIMERS) {
                collationKeyCreations++;
            }
        }
        return value.collationKey;
    }

    private CachedSortValue getCachedSortValue(BibtexEntry entry) {
        long fieldStart = PERF_TIMERS ? System.nanoTime() : 0L;
        Object rawValue = getRawField(entry);
        if (PERF_TIMERS) {
            getFieldNanos += System.nanoTime() - fieldStart;
        }

        CachedSortValue cached = sortValueCache.get(entry);
        if ((cached != null) && rawValuesEqual(cached.rawValue, rawValue)) {
            if (PERF_TIMERS) {
                cacheHits++;
            }
            return cached;
        }

        CachedSortValue rebuilt = new CachedSortValue();
        rebuilt.rawValue = rawValue;

        long normalizeStart = PERF_TIMERS ? System.nanoTime() : 0L;
        rebuildNormalizedValue(rebuilt);
        if (PERF_TIMERS) {
            normalizeNanos += System.nanoTime() - normalizeStart;
            if (cached == null) {
                cacheMisses++;
            } else {
                cacheRefreshes++;
            }
        }

        sortValueCache.put(entry, rebuilt);
        return rebuilt;
    }

    private void rebuildNormalizedValue(CachedSortValue value) {
        Object raw = value.rawValue;
        if (raw == null) {
            value.normalizedValue = null;
            return;
        }

        Object normalized = raw;

        // Now we know that the value is not null.
        if (isNameField) {
            normalized = AuthorList.fixAuthorForAlphabetization((String) raw);
            if (PERF_TIMERS) {
                nameNormalizations++;
            }
        } else if (isYearField) {
            /*
             * [ 1285977 ] Impossible to properly sort a numeric field
             *
             * http://sourceforge.net/tracker/index.php?func=detail&aid=1285977&group_id=92314&atid=600307
             */
            normalized = Util.toFourDigitYear((String) raw);
            if (PERF_TIMERS) {
                yearNormalizations++;
            }
        } else if (isMonthField) {
            /*
             * [ 1535044 ] Month sorting
             *
             * http://sourceforge.net/tracker/index.php?func=detail&aid=1535044&group_id=92314&atid=600306
             */
            normalized = Integer.valueOf(MonthUtil.getMonth((String) raw).number);
            if (PERF_TIMERS) {
                monthNormalizations++;
            }
        }

        if (isNumeric) {
            value.numericCandidate = true;
            if (normalized instanceof Integer) {
                value.numericParsed = true;
                value.numericValue = ((Integer) normalized).intValue();
            } else {
                try {
                    value.numericValue = Util.intValueOf(String.valueOf(normalized));
                    value.numericParsed = true;
                } catch (NumberFormatException ex) {
                    value.numericParsed = false;
                }
            }
            if (PERF_TIMERS) {
                numericNormalizations++;
            }
        }

        if ((normalized instanceof String) && !useFastStringCompare) {
            normalized = ((String) normalized).toLowerCase();
        }

        value.normalizedValue = normalized;
    }

    private Object getRawField(BibtexEntry entry) {
        if (isTypeHeader) {
            // Sort by type.
            return entry.getType().getName();
        }

        // If the field is author or editor, we rearrange names later so they are
        // sorted according to last name.
        return getField(entry);
    }

    private Object getField(BibtexEntry entry) {
        for (String aField : field) {
            Object o = entry.getFieldOrAlias(aField);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    private static boolean rawValuesEqual(Object oldValue, Object newValue) {
        return (oldValue == newValue) || ((oldValue != null) && oldValue.equals(newValue));
    }

    private static boolean isFastStringField(String[] fields) {
        if (fields.length != 1) {
            return false;
        }
        String f = fields[0];
        return f.equals("bibtexkey")
                || f.equals("doi")
                || f.equals("url")
                || f.equals("eprint")
                || f.equals("timestamp");
    }

    private void maybeLogPerf(boolean force) {
        if (!PERF_TIMERS) {
            return;
        }
        if (!force && ((compareCount % PERF_LOG_EVERY_COMPARISONS) != 0)) {
            return;
        }
        System.out.println("[FieldComparator timer] field=" + fieldName
                + ", comparisons=" + compareCount
                + ", totalMs=" + nanosToMs(totalNanos)
                + ", getFieldMs=" + nanosToMs(getFieldNanos)
                + ", normalizeMs=" + nanosToMs(normalizeNanos)
                + ", compareMs=" + nanosToMs(compareNanos)
                + ", nullComparisons=" + nullComparisons
                + ", integerComparisons=" + integerComparisons
                + ", collatorComparisons=" + collatorComparisons
                + ", fastTimestampComparisons=" + fastTimestampComparisons
                + ", fastStringComparisons=" + fastStringComparisons
                + ", nameNormalizations=" + nameNormalizations
                + ", yearNormalizations=" + yearNormalizations
                + ", monthNormalizations=" + monthNormalizations
                + ", numericNormalizations=" + numericNormalizations
                + ", cacheHits=" + cacheHits
                + ", cacheMisses=" + cacheMisses
                + ", cacheRefreshes=" + cacheRefreshes
                + ", collationKeyCreations=" + collationKeyCreations
                + ", cacheSize=" + sortValueCache.size()
                + ", multiplier=" + multiplier
                + ", thread=" + Thread.currentThread().getName()
                + ", edt=" + javax.swing.SwingUtilities.isEventDispatchThread());
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1000000L;
    }

    /**
     * Returns the field this Comparator compares by.
     *
     * @return The field name.
     */
    public String getFieldName() {
        return fieldName;
    }
}
