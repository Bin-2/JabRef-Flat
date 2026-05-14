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

import java.util.Comparator;
import java.util.List;

/**
 * This class represents a list of comparators. The first Comparator takes
 * precedence, and each time a Comparator returns 0, the next one is attempted.
 * If all comparators return 0 the final result will be 0.
 */
public class FieldComparatorStack<T> implements Comparator<T> {

    private static final boolean PERF_TIMERS = true;
    private static final long PERF_LOG_EVERY_COMPARISONS = 100000L;

    List<? extends Comparator<? super T>> comparators;

    private long compareCount = 0;
    private long totalNanos = 0;
    private long fallThroughCount = 0;
    private long zeroResultCount = 0;

    public FieldComparatorStack(List<? extends Comparator<? super T>> comparators) {
        this.comparators = comparators;
    }

    @Override
    public int compare(T o1, T o2) {
        long start = PERF_TIMERS ? System.nanoTime() : 0L;
        try {
            for (Comparator<? super T> comp : comparators) {
                int res = comp.compare(o1, o2);
                if (res != 0) {
                    return res;
                }
                if (PERF_TIMERS) {
                    fallThroughCount++;
                }
            }
            if (PERF_TIMERS) {
                zeroResultCount++;
            }
            return 0;
        } finally {
            if (PERF_TIMERS) {
                totalNanos += System.nanoTime() - start;
                compareCount++;
                maybeLogPerf(false);
            }
        }
    }

    private void maybeLogPerf(boolean force) {
        if (!PERF_TIMERS) {
            return;
        }
        if (!force && ((compareCount % PERF_LOG_EVERY_COMPARISONS) != 0)) {
            return;
        }
        System.out.println("[FieldComparatorStack timer] comparisons=" + compareCount
                + ", comparators=" + comparators.size()
                + ", totalMs=" + (totalNanos / 1000000L)
                + ", fallThroughCount=" + fallThroughCount
                + ", zeroResultCount=" + zeroResultCount
                + ", thread=" + Thread.currentThread().getName()
                + ", edt=" + javax.swing.SwingUtilities.isEventDispatchThread());
    }
}
