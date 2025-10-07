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

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class EntrySorter implements DatabaseChangeListener {

    private final TreeSet<BibtexEntry> sortedSet;
    private volatile boolean needsReindex = false;
    private String[] cachedIdArray;
    private BibtexEntry[] cachedEntryArray;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Comparator<BibtexEntry> comp;

    public EntrySorter(Map<String, BibtexEntry> entries, Comparator<BibtexEntry> comp) {
        this.comp = comp;
        long startTime = System.nanoTime();

        this.sortedSet = new TreeSet<>(comp);
        this.sortedSet.addAll(entries.values());
        this.needsReindex = true;
        index(); // Initial indexing

        long endTime = System.nanoTime();
        System.out.println("[EntrySorter NEW] Constructor time: " + (endTime - startTime) / 1000000.0 + " ms for " + entries.size() + " entries");
    }

    @Override
    public void databaseChanged(DatabaseChangeEvent e) {
        long startTime = System.nanoTime();

        lock.writeLock().lock();
        try {
            switch (e.getType()) {
                case ADDED_ENTRY:
                    sortedSet.add(e.getEntry());
                    needsReindex = true;
                    break;
                case REMOVED_ENTRY:
                    sortedSet.remove(e.getEntry());
                    needsReindex = true;
                    break;
                case CHANGED_ENTRY:
                    // TreeSet handles resorting automatically on re-insert
                    sortedSet.remove(e.getEntry());
                    sortedSet.add(e.getEntry());
                    needsReindex = true;
                    break;
            }
        } finally {
            lock.writeLock().unlock();
        }

        long endTime = System.nanoTime();
        System.out.println("[EntrySorter NEW] databaseChanged(" + e.getType() + ") time: " + (endTime - startTime) / 1000000.0 + " ms");
    }

    public void index() {
        if (!needsReindex) {
            return;
        }

        long startTime = System.nanoTime();

        lock.writeLock().lock();
        try {
            int size = sortedSet.size();
            cachedIdArray = new String[size];
            cachedEntryArray = new BibtexEntry[size];

            int i = 0;
            for (BibtexEntry entry : sortedSet) {
                cachedIdArray[i] = entry.getId();
                cachedEntryArray[i] = entry;
                i++;
            }
            needsReindex = false;
        } finally {
            lock.writeLock().unlock();
        }

        long endTime = System.nanoTime();
        System.out.println("[EntrySorter NEW] index() time: " + (endTime - startTime) / 1000000.0 + " ms for " + cachedEntryArray.length + " entries");
    }

    // Read methods use read lock
    public String getIdAt(int pos) {
        lock.readLock().lock();
        try {
            return cachedIdArray[pos];
        } finally {
            lock.readLock().unlock();
        }
    }

    public BibtexEntry getEntryAt(int pos) {
        lock.readLock().lock();
        try {
            return cachedEntryArray[pos];
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getEntryCount() {
        lock.readLock().lock();
        try {
            return cachedEntryArray != null ? cachedEntryArray.length : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isOutdated() {
        return false;
    }
}
