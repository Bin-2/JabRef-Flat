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
package net.sf.jabref.gui;

import com.formdev.flatlaf.FlatLightLaf;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

import javax.swing.*;
import javax.swing.plaf.TableUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;

import net.sf.jabref.*;
import net.sf.jabref.groups.EntryTableTransferHandler;
import net.sf.jabref.search.HitOrMissComparator;
import net.sf.jabref.specialfields.SpecialFieldsUtils;
import ca.odell.glazedlists.EventList;
import ca.odell.glazedlists.SortedList;
import ca.odell.glazedlists.event.ListEventListener;
import ca.odell.glazedlists.matchers.Matcher;
import ca.odell.glazedlists.swing.EventSelectionModel;
import ca.odell.glazedlists.swing.EventTableModel;
import ca.odell.glazedlists.swing.TableComparatorChooser;
import static com.formdev.flatlaf.util.ColorFunctions.lighten;
import java.awt.Component;
import net.sf.jabref.ThemeManager;

/**
 * The central table which displays the bibtex entries.
 *
 * User: alver Date: Oct 12, 2005 Time: 10:29:39 PM
 *
 */
public class MainTable extends JTable implements ThemeAwareComponent {

    private MainTableFormat tableFormat;
    private BasePanel panel;
    private SortedList<BibtexEntry> sortedForMarking, sortedForTable, sortedForSearch, sortedForGrouping;
    private boolean tableColorCodes, showingFloatSearch = false, showingFloatGrouping = false;
    private EventSelectionModel<BibtexEntry> selectionModel;
    private TableComparatorChooser<BibtexEntry> comparatorChooser;
    private JScrollPane pane;
    private Comparator<BibtexEntry> searchComparator, groupComparator,
            markingComparator = new IsMarkedComparator();
    private Matcher<BibtexEntry> searchMatcher, groupMatcher;
    private boolean initializingSorting = false;
    private Comparator<BibtexEntry> currentMarkingComparator = null;
    private Comparator<BibtexEntry> currentSearchComparator = null;
    private Comparator<BibtexEntry> currentGroupComparator = null;

    // needed to activate/deactivate the listener
    private final PersistenceTableColumnListener tableColumnListener;

    private static final boolean PERF_TIMERS = true;
    private static final long PERF_LOG_THRESHOLD_MS = 500L;
    private static final int PERF_RENDERER_LOG_EVERY = 50000;
    private static long perfRendererCalls = 0L;
    private static long perfRendererNs = 0L;

    private static long perfStart() {
        return PERF_TIMERS ? System.nanoTime() : 0L;
    }

    private static void perfLog(String label, long startNs) {
        if (!PERF_TIMERS || startNs == 0L) {
            return;
        }
        long elapsedNs = System.nanoTime() - startNs;
        long elapsedMs = elapsedNs / 1000000L;
        if (elapsedMs >= PERF_LOG_THRESHOLD_MS) {
            System.out.println("[MainTable timer] " + label
                    + " took " + elapsedMs + " ms (" + elapsedNs + " ns)"
                    + " thread=" + Thread.currentThread().getName()
                    + " edt=" + SwingUtilities.isEventDispatchThread());
        }
    }

    private static void perfLogRenderer(long startNs) {
        if (!PERF_TIMERS || startNs == 0L) {
            return;
        }
        long elapsedNs = System.nanoTime() - startNs;
        perfRendererCalls++;
        perfRendererNs += elapsedNs;
        if ((perfRendererCalls % PERF_RENDERER_LOG_EVERY) == 0L) {
            System.out.println("[MainTable timer] getCellRenderer periodic calls=" + perfRendererCalls
                    + ", totalMs=" + (perfRendererNs / 1000000L)
                    + ", avgNs=" + (perfRendererNs / perfRendererCalls)
                    + " thread=" + Thread.currentThread().getName()
                    + " edt=" + SwingUtilities.isEventDispatchThread());
        }
    }

    private static int safeEventListSize(EventList<?> list) {
        if (list == null) {
            return -1;
        }
        try {
            return list.size();
        } catch (RuntimeException ex) {
            return -2;
        }
    }

    // Constants used to define how a cell should be rendered.
    public static final int REQUIRED = 1, OPTIONAL = 2,
            REQ_STRING = 1,
            REQ_NUMBER = 2,
            OPT_STRING = 3,
            OTHER = 3,
            BOOLEAN = 4,
            ICON_COL = 8; // Constant to indicate that an icon cell renderer should be used.

    static {
        updateRenderers();
    }

    public MainTable(MainTableFormat tableFormat, EventList<BibtexEntry> list, JabRefFrame frame,
            BasePanel panel) {
        super();
        long constructorStartNs = perfStart();
        long blockStartNs;

        blockStartNs = perfStart();
        addFocusListener(Globals.focusListener);
        setAutoResizeMode(Globals.prefs.getInt("autoResizeMode"));
        this.tableFormat = tableFormat;
        this.panel = panel;
        perfLog("constructor focus/autoresize/basic fields listSize=" + safeEventListSize(list), blockStartNs);

        // This SortedList has a Comparator controlled by the TableComparatorChooser
        // we are going to install, which responds to user sorting selections:
        blockStartNs = perfStart();
        sortedForTable = new SortedList<BibtexEntry>(list, null);
        perfLog("constructor new sortedForTable size=" + safeEventListSize(sortedForTable), blockStartNs);

        // This SortedList applies afterwards, and floats marked entries:
        blockStartNs = perfStart();
        sortedForMarking = new SortedList<BibtexEntry>(sortedForTable, null);
        perfLog("constructor new sortedForMarking size=" + safeEventListSize(sortedForMarking), blockStartNs);

        // This SortedList applies afterwards, and can float search hits:
        blockStartNs = perfStart();
        sortedForSearch = new SortedList<BibtexEntry>(sortedForMarking, null);
        perfLog("constructor new sortedForSearch size=" + safeEventListSize(sortedForSearch), blockStartNs);

        // This SortedList applies afterwards, and can float grouping hits:
        blockStartNs = perfStart();
        sortedForGrouping = new SortedList<BibtexEntry>(sortedForSearch, null);
        perfLog("constructor new sortedForGrouping size=" + safeEventListSize(sortedForGrouping), blockStartNs);

        searchMatcher = null;
        groupMatcher = null;
        searchComparator = null;//new HitOrMissComparator(searchMatcher);
        groupComparator = null;//new HitOrMissComparator(groupMatcher);

        blockStartNs = perfStart();
        EventTableModel<BibtexEntry> tableModel = new EventTableModel<BibtexEntry>(sortedForGrouping, tableFormat);
        perfLog("constructor new EventTableModel rows=" + safeEventListSize(sortedForGrouping), blockStartNs);

        blockStartNs = perfStart();
        setModel(tableModel);
        perfLog("constructor setModel columns=" + tableModel.getColumnCount(), blockStartNs);

        blockStartNs = perfStart();
        tableColorCodes = Globals.prefs.getBoolean("tableColorCodesOn");
        selectionModel = new EventSelectionModel<BibtexEntry>(sortedForGrouping);
        setSelectionModel(selectionModel);
        perfLog("constructor selection model rows=" + safeEventListSize(sortedForGrouping), blockStartNs);

        blockStartNs = perfStart();
        pane = new JScrollPane(this);
        pane.setBorder(BorderFactory.createEmptyBorder());
//        pane.getViewport().setBackground(Globals.prefs.getColor("tableBackground"));
//        setGridColor(Globals.prefs.getColor("gridColor"));
        if (Globals.prefs.getBoolean("tableShowGrid")) {
            setShowGrid(true);
        } else {
            setShowGrid(false);
            setIntercellSpacing(new Dimension(0, 0));
        }
        perfLog("constructor scrollPane/grid", blockStartNs);

        blockStartNs = perfStart();
        this.setTableHeader(new PreventDraggingJTableHeader(this.getColumnModel()));
        perfLog("constructor tableHeader", blockStartNs);

        blockStartNs = perfStart();
        comparatorChooser = this.createTableComparatorChooser(this, sortedForTable,
                TableComparatorChooser.MULTIPLE_COLUMN_KEYBOARD);
        perfLog("constructor createTableComparatorChooser rows=" + safeEventListSize(sortedForTable), blockStartNs);

        blockStartNs = perfStart();
        this.tableColumnListener = new PersistenceTableColumnListener(this);
        perfLog("constructor PersistenceTableColumnListener", blockStartNs);
        /*if (Globals.prefs.getBoolean(PersistenceTableColumnListener.ACTIVATE_PREF_KEY)) {
            getColumnModel().addColumnModelListener(this.tableColumnListener );
        }*/

        // TODO: Figure out whether this call is needed.
        blockStartNs = perfStart();
        getSelected();
        perfLog("constructor getSelected warmup", blockStartNs);

        // enable DnD
        blockStartNs = perfStart();
        setDragEnabled(true);
        TransferHandler xfer = new EntryTableTransferHandler(this, frame, panel);
        setTransferHandler(xfer);
        pane.setTransferHandler(xfer);
        perfLog("constructor drag/drop setup", blockStartNs);

        initializingSorting = true;
        try {
            blockStartNs = perfStart();
            setupComparatorChooser();
            perfLog("constructor setupComparatorChooser", blockStartNs);

            blockStartNs = perfStart();
            refreshSorting();
            perfLog("constructor refreshSorting", blockStartNs);
        } finally {
            initializingSorting = false;
        }

        blockStartNs = perfStart();
        setWidths();
        perfLog("constructor setWidths", blockStartNs);

        blockStartNs = perfStart();
        this.setOpaque(false);
        perfLog("constructor setOpaque", blockStartNs);
        // this.setBackground(new Color(249, 250, 251));

        // Register for theme changes
        blockStartNs = perfStart();
        ThemeWatcher.register(this);
        perfLog("constructor ThemeWatcher.register", blockStartNs);

        perfLog("constructor total rows=" + safeEventListSize(sortedForGrouping)
                + ", columns=" + getColumnCount(), constructorStartNs);

    }

    public void refreshSorting() {
        long totalStartNs = perfStart();
        long blockStartNs;

        Comparator<BibtexEntry> newMarkingComparator = Globals.prefs.getBoolean("floatMarkedEntries")
                ? markingComparator : null;
        Comparator<BibtexEntry> newSearchComparator = searchComparator;
        Comparator<BibtexEntry> newGroupComparator = groupComparator;

        if (currentMarkingComparator != newMarkingComparator) {
            blockStartNs = perfStart();
            sortedForMarking.getReadWriteLock().writeLock().lock();
            try {
                sortedForMarking.setComparator(newMarkingComparator);
                currentMarkingComparator = newMarkingComparator;
            } finally {
                sortedForMarking.getReadWriteLock().writeLock().unlock();
            }
            perfLog("refreshSorting set marking comparator active=" + (newMarkingComparator != null)
                    + ", rows=" + safeEventListSize(sortedForMarking), blockStartNs);
        }

        if (currentSearchComparator != newSearchComparator) {
            blockStartNs = perfStart();
            sortedForSearch.getReadWriteLock().writeLock().lock();
            try {
                sortedForSearch.setComparator(newSearchComparator);
                currentSearchComparator = newSearchComparator;
            } finally {
                sortedForSearch.getReadWriteLock().writeLock().unlock();
            }
            perfLog("refreshSorting set search comparator active=" + (newSearchComparator != null)
                    + ", rows=" + safeEventListSize(sortedForSearch), blockStartNs);
        }

        if (currentGroupComparator != newGroupComparator) {
            blockStartNs = perfStart();
            sortedForGrouping.getReadWriteLock().writeLock().lock();
            try {
                sortedForGrouping.setComparator(newGroupComparator);
                currentGroupComparator = newGroupComparator;
            } finally {
                sortedForGrouping.getReadWriteLock().writeLock().unlock();
            }
            perfLog("refreshSorting set group comparator active=" + (newGroupComparator != null)
                    + ", rows=" + safeEventListSize(sortedForGrouping), blockStartNs);
        }

        perfLog("refreshSorting total rows=" + safeEventListSize(sortedForGrouping), totalStartNs);
    }

    /**
     * Adds a sorting rule that floats hits to the top, and causes non-hits to
     * be grayed out:
     *
     * @param m The Matcher that determines if an entry is a hit or not.
     */
    public void showFloatSearch(Matcher<BibtexEntry> m) {
        long startNs = perfStart();
        showingFloatSearch = true;
        searchMatcher = m;
        searchComparator = (m == null) ? null : new HitOrMissComparator(m);
        refreshSorting();
        scrollTo(0);
        perfLog("showFloatSearch matcher=" + (m != null) + ", rows=" + safeEventListSize(sortedForGrouping), startNs);
    }

    /**
     * Removes sorting by search results, and graying out of non-hits.
     */
    public void stopShowingFloatSearch() {
        long startNs = perfStart();
        showingFloatSearch = false;
        searchMatcher = null;
        searchComparator = null;
        refreshSorting();
        perfLog("stopShowingFloatSearch rows=" + safeEventListSize(sortedForGrouping), startNs);
    }

    /**
     * Adds a sorting rule that floats group hits to the top, and causes
     * non-hits to be grayed out:
     *
     * @param m The Matcher that determines if an entry is a in the current
     * group selection or not.
     */
    public void showFloatGrouping(Matcher<BibtexEntry> m) {
        long startNs = perfStart();
        showingFloatGrouping = true;
        groupMatcher = m;
        groupComparator = (m == null) ? null : new HitOrMissComparator(m);
        refreshSorting();
        perfLog("showFloatGrouping matcher=" + (m != null) + ", rows=" + safeEventListSize(sortedForGrouping), startNs);
    }

    public boolean isShowingFloatSearch() {
        return showingFloatSearch;
    }

    /**
     * Removes sorting by group, and graying out of non-hits.
     */
    public void stopShowingFloatGrouping() {
        long startNs = perfStart();
        showingFloatGrouping = false;
        groupMatcher = null;
        groupComparator = null;
        refreshSorting();
        perfLog("stopShowingFloatGrouping rows=" + safeEventListSize(sortedForGrouping), startNs);
    }

    public EventList<BibtexEntry> getTableRows() {
        return sortedForGrouping;
    }

    public void addSelectionListener(ListEventListener<BibtexEntry> listener) {
        getSelected().addListEventListener(listener);
    }

    public JScrollPane getPane() {
        return pane;
    }

    @Override
    public TableCellRenderer getCellRenderer(int row, int column) {
        long rendererStartNs = perfStart();

        BibtexEntry entry = getEntrySafely(row);

        int score = -3;
        DefaultTableCellRenderer renderer = defRenderer;

        if (!showingFloatSearch || matches(entry, searchMatcher)) {
            score++;
        }
        if (!showingFloatGrouping || matches(entry, groupMatcher)) {
            score += 2;
        }

        int marking = isMarked(entry);

        // Grayed-out logic for filtered rows stays unchanged
        if (score < -1) {
            if (column == 0) {
                veryGrayedOutNumberRenderer.setNumber(row);
                renderer = veryGrayedOutNumberRenderer;
            } else {
                renderer = veryGrayedOutRenderer;
            }
        } else if (score == -1) {
            if (column == 0) {
                grayedOutNumberRenderer.setNumber(row);
                renderer = grayedOutNumberRenderer;
            } else {
                renderer = grayedOutRenderer;
            }
        } else if (column == 0) {
            // Important change:
            // When table color coding is disabled, keep column 0 neutral.
            compRenderer.setNumber(row);
            renderer = compRenderer;

            if (tableColorCodes) {
                if (!isComplete(entry)) {
                    incRenderer.setNumber(row);
                    renderer = incRenderer;
                } else if (marking > 0) {
                    int boundedMarking = Math.min(marking, Util.MARK_COLOR_LEVELS);
                    markedNumberRenderers[boundedMarking - 1].setNumber(row);
                    renderer = markedNumberRenderers[boundedMarking - 1];
                }
            }

            renderer.setHorizontalAlignment(JLabel.CENTER);
        } else if (tableColorCodes) {
            int status = getCellStatus(entry, column);
            if (status == REQUIRED) {
                renderer = reqRenderer;
            } else if (status == OPTIONAL) {
                renderer = optRenderer;
            } else if (status == BOOLEAN) {
                renderer = (DefaultTableCellRenderer) getDefaultRenderer(Boolean.class);
            }
        }

        // Keep non-zero column marking logic as before
        if ((column != 0) && (marking > 0)) {
            marking = Math.min(marking, Util.MARK_COLOR_LEVELS);
            renderer = markedRenderers[marking - 1];
        }

        perfLogRenderer(rendererStartNs);
        return renderer;
    }

    private BibtexEntry getEntrySafely(int row) {
        try {
            return sortedForGrouping.get(row);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public void setWidths() {
        long startNs = perfStart();
        // Setting column widths:
        int ncWidth = Globals.prefs.getInt("numberColWidth");
        String[] widths = Globals.prefs.getStringArray("columnWidths");
        TableColumnModel cm = getColumnModel();
        cm.getColumn(0).setPreferredWidth(ncWidth);
        for (int i = 1; i < tableFormat.padleft; i++) {

            // Check if the Column is an extended RankingColumn (and not a compact-ranking column)
            // If this is the case, set a certain Column-width,
            // because the RankingIconColumn needs some more width
            if (tableFormat.isRankingColumn(i) && !Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_RANKING_COMPACT)) {
                // Lock the width of ranking icon column.
                cm.getColumn(i).setPreferredWidth(GUIGlobals.WIDTH_ICON_COL_RANKING);
                cm.getColumn(i).setMinWidth(GUIGlobals.WIDTH_ICON_COL_RANKING);
                cm.getColumn(i).setMaxWidth(GUIGlobals.WIDTH_ICON_COL_RANKING);
            } else {
                // Lock the width of icon columns.
                cm.getColumn(i).setPreferredWidth(GUIGlobals.WIDTH_ICON_COL);
                cm.getColumn(i).setMinWidth(GUIGlobals.WIDTH_ICON_COL);
                cm.getColumn(i).setMaxWidth(GUIGlobals.WIDTH_ICON_COL);
            }

        }
        int modelColumnCount = getModel().getColumnCount();
        for (int i = tableFormat.padleft; i < modelColumnCount; i++) {
            try {
                cm.getColumn(i).setPreferredWidth(Integer.parseInt(widths[i - tableFormat.padleft]));
            } catch (Throwable ex) {
                Globals.logger("Exception while setting column widths. Choosing default.");
                cm.getColumn(i).setPreferredWidth(GUIGlobals.DEFAULT_FIELD_LENGTH);
            }

        }
        perfLog("setWidths columns=" + modelColumnCount, startNs);
    }

    public BibtexEntry getEntryAt(int row) {
        return sortedForGrouping.get(row);
    }

    /**
     * @return the return value is never null
     */
    public BibtexEntry[] getSelectedEntries() {
        final BibtexEntry[] BE_ARRAY = new BibtexEntry[0];
        return getSelected().toArray(BE_ARRAY);
    }

    public List<Boolean> getCurrentSortOrder() {
        List<Boolean> order = new ArrayList<Boolean>();
        List<Integer> sortCols = comparatorChooser.getSortingColumns();
        for (Integer i : sortCols) {
            order.add(comparatorChooser.isColumnReverse(i));
        }
        return order;
    }

    public List<String> getCurrentSortFields() {
        List<Integer> sortCols = comparatorChooser.getSortingColumns();
        List<String> fields = new ArrayList<String>();
        for (Integer i : sortCols) {
            String name = tableFormat.getColumnType(i);
            if (name != null) {
                fields.add(name.toLowerCase());
            }
        }
        return fields;
    }

    /**
     * This method sets up what Comparators are used for the various table
     * columns. The ComparatorChooser enables and disables such Comparators as
     * the user clicks columns, but this is where the Comparators are defined.
     * Also, the ComparatorChooser is initialized with the sort order defined in
     * Preferences.
     */
    @SuppressWarnings("unchecked")
    private void setupComparatorChooser() {
        long totalStartNs = perfStart();
        long blockStartNs;

        // First column:
        blockStartNs = perfStart();
        List<Comparator> comparators = comparatorChooser.getComparatorsForColumn(0);
        comparators.clear();
        comparators.add(new FirstColumnComparator(panel.database()));
        perfLog("setupComparatorChooser first column", blockStartNs);

        // Icon columns:
        blockStartNs = perfStart();
        for (int i = 1; i < tableFormat.padleft; i++) {
            comparators = comparatorChooser.getComparatorsForColumn(i);
            comparators.clear();
            String[] iconField = tableFormat.getIconTypeForColumn(i);

            if (iconField[0].equals(SpecialFieldsUtils.FIELDNAME_RANKING)) {
                comparators.add(new RankingFieldComparator());
            } else {
                comparators.add(new IconComparator(iconField));
            }
        }
        perfLog("setupComparatorChooser icon columns count=" + Math.max(0, tableFormat.padleft - 1), blockStartNs);

        // Remaining columns:
        blockStartNs = perfStart();
        for (int i = tableFormat.padleft; i < tableFormat.getColumnCount(); i++) {
            comparators = comparatorChooser.getComparatorsForColumn(i);
            comparators.clear();
            comparators.add(new FieldComparator(tableFormat.getColumnName(i).toLowerCase()));
        }
        perfLog("setupComparatorChooser field columns count=" + Math.max(0, tableFormat.getColumnCount() - tableFormat.padleft), blockStartNs);

        // Set initial sort columns:
        // Default sort order:
        blockStartNs = perfStart();
        String[] sortFields = new String[]{
            Globals.prefs.get(JabRefPreferences.PRIMARY_SORT_FIELD),
            Globals.prefs.get(JabRefPreferences.SECONDARY_SORT_FIELD),
            Globals.prefs.get(JabRefPreferences.TERTIARY_SORT_FIELD)
        };
        boolean[] sortDirections = new boolean[]{
            Globals.prefs.getBoolean(JabRefPreferences.PRIMARY_SORT_DESCENDING),
            Globals.prefs.getBoolean(JabRefPreferences.SECONDARY_SORT_DESCENDING),
            Globals.prefs.getBoolean(JabRefPreferences.TERTIARY_SORT_DESCENDING)
        }; // descending
        perfLog("setupComparatorChooser read sort prefs", blockStartNs);

        blockStartNs = perfStart();
        int appendedComparators = 0;
        sortedForTable.getReadWriteLock().writeLock().lock();
        try {
            for (int i = 0; i < sortFields.length; i++) {
                int index = -1;
                if (!sortFields[i].startsWith(MainTableFormat.ICON_COLUMN_PREFIX)) {
                    index = tableFormat.getColumnIndex(sortFields[i]);
                } else {
                    for (int j = 0; j < tableFormat.getColumnCount(); j++) {
                        if (sortFields[i].equals(tableFormat.getColumnType(j))) {
                            index = j;
                            break;
                        }
                    }
                }
                if (index >= 0) {
                    long appendStartNs = perfStart();
                    comparatorChooser.appendComparator(index, 0, sortDirections[i]);
                    perfLog("setupComparatorChooser appendComparator sortField=" + sortFields[i]
                            + ", index=" + index + ", descending=" + sortDirections[i]
                            + ", rows=" + safeEventListSize(sortedForTable), appendStartNs);
                    appendedComparators++;
                }
            }
        } finally {
            sortedForTable.getReadWriteLock().writeLock().unlock();
        }
        perfLog("setupComparatorChooser append comparators count=" + appendedComparators
                + ", rows=" + safeEventListSize(sortedForTable), blockStartNs);

        // Add action listener so we can remember the sort order:
        blockStartNs = perfStart();
        comparatorChooser.addSortActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent actionEvent) {
                // Get the information about the current sort order:
                List<String> fields = getCurrentSortFields();
                List<Boolean> order = getCurrentSortOrder();
                // Update preferences:
                int count = Math.min(fields.size(), order.size());
                if (count >= 1) {
                    Globals.prefs.put(JabRefPreferences.PRIMARY_SORT_FIELD, fields.get(0));
                    Globals.prefs.putBoolean(JabRefPreferences.PRIMARY_SORT_DESCENDING, order.get(0));
                }
                if (count >= 2) {
                    Globals.prefs.put(JabRefPreferences.SECONDARY_SORT_FIELD, fields.get(1));
                    Globals.prefs.putBoolean(JabRefPreferences.SECONDARY_SORT_DESCENDING, order.get(1));
                } else {
                    Globals.prefs.put(JabRefPreferences.SECONDARY_SORT_FIELD, "");
                    Globals.prefs.putBoolean(JabRefPreferences.SECONDARY_SORT_DESCENDING, false);
                }
                if (count >= 3) {
                    Globals.prefs.put(JabRefPreferences.TERTIARY_SORT_FIELD, fields.get(2));
                    Globals.prefs.putBoolean(JabRefPreferences.TERTIARY_SORT_DESCENDING, order.get(2));
                } else {
                    Globals.prefs.put(JabRefPreferences.TERTIARY_SORT_FIELD, "");
                    Globals.prefs.putBoolean(JabRefPreferences.TERTIARY_SORT_DESCENDING, false);
                }
            }

        });
        perfLog("setupComparatorChooser add persist sort listener", blockStartNs);
        perfLog("setupComparatorChooser total columns=" + tableFormat.getColumnCount()
                + ", rows=" + safeEventListSize(sortedForTable), totalStartNs);

    }

    public int getCellStatus(int row, int col) {
        return getCellStatus(getEntrySafely(row), col);
    }

    private int getCellStatus(BibtexEntry be, int col) {
        if (be == null) {
            return OTHER;
        }

        BibtexEntryType type = be.getType();
        if (type == null) {
            return OTHER;
        }

        String columnName = getColumnName(col);
        if (columnName == null) {
            return OTHER;
        }
        columnName = columnName.toLowerCase();

        if (columnName.equals(BibtexFields.KEY_FIELD) || type.isRequired(columnName)) {
            return REQUIRED;
        }
        if (type.isOptional(columnName)) {
            return OPTIONAL;
        }
        return OTHER;
    }

    /**
     * Use with caution! If you modify an entry in the table, the selection
     * changes
     *
     * You can avoid it with
     * <code>.getSelected().getReadWriteLock().writeLock().lock()</code> and
     * then <code>.unlock()</code>
     */
    public EventList<BibtexEntry> getSelected() {
        return selectionModel.getSelected();
    }

    /**
     * Selects the given row
     *
     * @param row the row to select
     */
    public void setSelected(int row) {
        selectionModel.setSelectionInterval(row, row);
    }

    /**
     * Adds the given row to the selection
     *
     * @param row the row to add to the selection
     */
    public void addSelection(int row) {
        this.selectionModel.addSelectionInterval(row, row);
    }

    public int findEntry(BibtexEntry entry) {
        //System.out.println(sortedForGrouping.indexOf(entry));
        return sortedForGrouping.indexOf(entry);
    }

    public String[] getIconTypeForColumn(int column) {
        return tableFormat.getIconTypeForColumn(column);
    }

    private boolean matches(int row, Matcher<BibtexEntry> m) {
        return matches(getEntrySafely(row), m);
    }

    private boolean matches(BibtexEntry entry, Matcher<BibtexEntry> m) {
        if ((m == null) || (entry == null)) {
            return false;
        }
        try {
            return m.matches(entry);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean isComplete(int row) {
        return isComplete(getEntrySafely(row));
    }

    private boolean isComplete(BibtexEntry be) {
        return be != null && be.hasAllRequiredFields(panel.database());
    }

    private int isMarked(int row) {
        return isMarked(getEntrySafely(row));
    }

    private int isMarked(BibtexEntry be) {
        return be == null ? 0 : Util.isMarked(be);
    }

    public void scrollTo(int y) {
        JScrollBar scb = pane.getVerticalScrollBar();
        scb.setValue(y * scb.getUnitIncrement(1));
    }

    /**
     * updateFont
     */
    public void updateFont() {
        setFont(GUIGlobals.CURRENTFONT);
        setRowHeight(Globals.prefs.getInt("tableRowPadding") + GUIGlobals.CURRENTFONT.getSize());
    }

    /**
     * Reload appearance preferences that are cached by an existing table.
     * This avoids reconstructing the table model for appearance-only changes.
     */
    public void updateAppearancePreferences() {
        tableColorCodes = Globals.prefs.getBoolean("tableColorCodesOn");

        boolean showGrid = Globals.prefs.getBoolean("tableShowGrid");
        setShowGrid(showGrid);
        setIntercellSpacing(showGrid ? new Dimension(1, 1) : new Dimension(0, 0));

        updateFont();
        revalidate();
        repaint();
    }

    public void ensureVisible(int row) {
        JScrollBar vert = pane.getVerticalScrollBar();
        int y = row * getRowHeight();
        if ((y < vert.getValue()) || (y > vert.getValue() + vert.getVisibleAmount()) && !showingFloatSearch) {
            scrollToCenter(row, 1);
        }

    }

    public void scrollToCenter(int rowIndex, int vColIndex) {
        if (!(this.getParent() instanceof JViewport)) {
            return;
        }

        JViewport viewport = (JViewport) this.getParent();

        // This rectangle is relative to the table where the
        // northwest corner of cell (0,0) is always (0,0).
        Rectangle rect = this.getCellRect(rowIndex, vColIndex, true);

        // The location of the view relative to the table
        Rectangle viewRect = viewport.getViewRect();

        // Translate the cell location so that it is relative
        // to the view, assuming the northwest corner of the
        // view is (0,0).
        rect.setLocation(rect.x - viewRect.x, rect.y - viewRect.y);

        // Calculate location of rect if it were at the center of view
        int centerX = (viewRect.width - rect.width) / 2;
        int centerY = (viewRect.height - rect.height) / 2;

        // Fake the location of the cell so that scrollRectToVisible
        // will move the cell to the center
        if (rect.x < centerX) {
            centerX = -centerX;
        }
        if (rect.y < centerY) {
            centerY = -centerY;
        }
        rect.translate(centerX, centerY);

        // Scroll the area into view.
        viewport.scrollRectToVisible(rect);

        revalidate();
        repaint();
    }

    private static GeneralRenderer defRenderer, reqRenderer, optRenderer, grayedOutRenderer,
            veryGrayedOutRenderer;

    private static GeneralRenderer[] markedRenderers;

    private static IncompleteRenderer incRenderer;
    private static CompleteRenderer compRenderer,
            grayedOutNumberRenderer,
            veryGrayedOutNumberRenderer;

    private static CompleteRenderer[] markedNumberRenderers;

    public static void updateRenderers() {
        long startNs = perfStart();
        // Always use FlatLaf's current theme colors as base
        Color tableBackground = UIManager.getColor("Table.background");
        Color tableForeground = UIManager.getColor("Table.foreground");
        Color selectionBackground = UIManager.getColor("Table.selectionBackground");

        // If FlatLaf doesn't provide these (shouldn't happen with FlatLaf), use sensible defaults
        if (tableBackground == null) {
            tableBackground = Color.WHITE;
        }
        if (tableForeground == null) {
            tableForeground = Color.BLACK;
        }
        if (selectionBackground == null) {
            selectionBackground = new Color(51, 153, 255);
        }

        defRenderer = new GeneralRenderer(tableBackground, tableForeground);

        // Calculate derived colors that work well with the current theme
        Color reqFieldBg = calculateRequiredFieldColor(tableBackground);
        Color optFieldBg = calculateOptionalFieldColor(tableBackground);
        Color incompleteBg = calculateIncompleteColor(tableBackground);
        Color grayedOutBg = blend(tableBackground, tableForeground, 0.85f);
        Color veryGrayedOutBg = blend(tableBackground, tableForeground, 0.70f);
        Color grayedOutText = blend(tableForeground, tableBackground, 0.5f);
        Color veryGrayedOutText = blend(tableForeground, tableBackground, 0.3f);

        reqRenderer = new GeneralRenderer(reqFieldBg, tableForeground);
        optRenderer = new GeneralRenderer(optFieldBg, tableForeground);

        incRenderer = new IncompleteRenderer(incompleteBg);
        compRenderer = new CompleteRenderer(tableBackground);
        grayedOutNumberRenderer = new CompleteRenderer(grayedOutBg);
        veryGrayedOutNumberRenderer = new CompleteRenderer(veryGrayedOutBg);

        grayedOutRenderer = new GeneralRenderer(grayedOutBg, grayedOutText, selectionBackground);
        veryGrayedOutRenderer = new GeneralRenderer(veryGrayedOutBg, veryGrayedOutText, selectionBackground);

        // MARKED RENDERERS - Keep as they are (custom user colors)
        // MARKED RENDERERS - Use the original colors but don't modify preferences
        boolean useThemeSemanticColors
                = ThemeColorPalette.isSemanticColorsEnabled();

        markedRenderers = new GeneralRenderer[Util.MARK_COLOR_LEVELS];
        markedNumberRenderers = new CompleteRenderer[Util.MARK_COLOR_LEVELS];

        for (int i = 0; i < Util.MARK_COLOR_LEVELS; i++) {
            Color tableColor;

            if (useThemeSemanticColors) {
                tableColor = ThemeColorPalette.getMarkColor(i);
            } else {
                Color originalColor
                        = Globals.prefs.getColor("markedEntryBackground" + i);

                tableColor = adjustColorForTheme(originalColor);
            }

            markedRenderers[i] = new GeneralRenderer(
                    tableColor,
                    tableForeground,
                    blend(tableColor, selectionBackground, 0.3f));

            markedNumberRenderers[i]
                    = new CompleteRenderer(tableColor);
        }

        perfLog("updateRenderers total", startNs);
    }

    private static Color adjustColorForTheme(Color original) {
        // Adjust the original marking color to work better with current theme
        // but don't modify the original preference
        Color tableBg = UIManager.getColor("Table.background");
        if (tableBg != null && ThemeManager.isDarkTheme()) {
            // Lighten colors for dark theme, darken for light theme
            return blend(original, tableBg, 0.3f);
        }
        return original;
    }

    private static GeneralRenderer createNewRenderer(Color background, Color foreground) {
        return new GeneralRenderer(background, foreground);
    }

    // Helper methods for calculating theme-appropriate colors
    private static Color calculateRequiredFieldColor(Color base) {
        // Light red tint for light theme, dark red for dark theme
        return ThemeManager.isDarkTheme()
                ? blend(base, new Color(255, 100, 100), 0.15f)
                : blend(base, new Color(255, 200, 200), 0.3f);
    }

    private static Color calculateOptionalFieldColor(Color base) {
        // Light yellow tint for light theme, dark yellow for dark theme
        return ThemeManager.isDarkTheme()
                ? blend(base, new Color(255, 255, 100), 0.1f)
                : blend(base, new Color(255, 255, 200), 0.3f);
    }

    private static Color calculateIncompleteColor(Color base) {
        // More pronounced color for incomplete entries
        return ThemeManager.isDarkTheme()
                ? blend(base, new Color(255, 100, 100), 0.25f)
                : blend(base, new Color(255, 150, 150), 0.4f);
    }

    //////////////////////////////////////////////////////////////////////////////
    private static Color blend(Color base, Color overlay, float alphaOverlay) {
        if (base == null) {
            base = Color.WHITE;
        }
        if (overlay == null) {
            overlay = Color.BLACK;
        }

        float a = Math.max(0f, Math.min(1f, alphaOverlay));
        int r = Math.round((1 - a) * base.getRed() + a * overlay.getRed());
        int g = Math.round((1 - a) * base.getGreen() + a * overlay.getGreen());
        int b = Math.round((1 - a) * base.getBlue() + a * overlay.getBlue());

        // Ensure values are in valid range
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        return new Color(r, g, b);
    }

    private static Color mixColors(Color one, Color two) {
        return new Color((one.getRed() + two.getRed()) / 2, (one.getGreen() + two.getGreen()) / 2,
                (one.getBlue() + two.getBlue()) / 2);
    }

    static class IncompleteRenderer extends GeneralRenderer {

        public IncompleteRenderer(Color color) {
            super(color);
            super.setToolTipText(Globals.lang("This entry is incomplete"));
        }

        public IncompleteRenderer() {
            super(Globals.prefs.getColor("incompleteEntryBackground"));
            super.setToolTipText(Globals.lang("This entry is incomplete"));
        }

        protected void setNumber(int number) {
            super.setValue(String.valueOf(number + 1));
        }

        protected void setValue(Object value) {

        }
    }

    static class CompleteRenderer extends GeneralRenderer {

        public CompleteRenderer(Color color) {
            super(color);
        }

        protected void setNumber(int number) {
            super.setValue(String.valueOf(number + 1));
        }

        protected void setValue(Object value) {

        }
    }

    public TableComparatorChooser<BibtexEntry> createTableComparatorChooser(JTable table, SortedList<BibtexEntry> list,
            Object sortingStrategy) {
        long startNs = perfStart();
        long blockStartNs = perfStart();
        final TableComparatorChooser<BibtexEntry> result = TableComparatorChooser.install(table, list, sortingStrategy);
        perfLog("createTableComparatorChooser install rows=" + safeEventListSize(list), blockStartNs);
        blockStartNs = perfStart();
        result.addSortActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // We need to reset the stack of sorted list each time sorting order
                // changes, or the sorting breaks down:
                if (!initializingSorting) {
                    refreshSorting();
                }
            }
        });
        perfLog("createTableComparatorChooser add refresh listener", blockStartNs);
        perfLog("createTableComparatorChooser total rows=" + safeEventListSize(list), startNs);
        return result;
    }

    /**
     * Morten Alver: This override is a workaround NullPointerException when
     * dragging stuff into the table. I found this in a forum, but have no idea
     * why it works.
     *
     * @param newUI
     */
    public void setUI(TableUI newUI) {
        long startNs = perfStart();
        super.setUI(newUI);
        TransferHandler handler = getTransferHandler();
        setTransferHandler(null);
        setTransferHandler(handler);
        perfLog("setUI newUI=" + (newUI == null ? "null" : newUI.getClass().getName()), startNs);
    }

    /**
     * Get the first comparator set up for the given column.
     *
     * @param index The column number.
     * @return The Comparator, or null if none is set.
     */
    @SuppressWarnings("unchecked")
    public Comparator<BibtexEntry> getComparatorForColumn(int index) {
        List<Comparator> l = comparatorChooser.getComparatorsForColumn(index);
        return l.size() == 0 ? null : l.get(0);
    }

    /**
     * Find out which column is set as sort column.
     *
     * @param number The position in the sort hierarchy (primary, secondary,
     * etc.)
     * @return The sort column number.
     */
    public int getSortingColumn(int number) {
        List<Integer> l = comparatorChooser.getSortingColumns();
        if (l.size() <= number) {
            return -1;
        } else {
            return l.get(number);
        }
    }

    public PersistenceTableColumnListener getTableColumnListener() {
        return tableColumnListener;
    }

    /**
     * Returns the List of entries sorted by a user-selected term. This is the
     * sorting before marking, search etc. applies.
     *
     * Note: The returned List must not be modified from the outside
     *
     * @return The sorted list of entries.
     */
    public SortedList<BibtexEntry> getSortedForTable() {
        return sortedForTable;
    }

    public void forceIconUpdate() {
        GUIGlobals.clearTableIconCache();
        GUIGlobals.initTableIcons();

        if (tableFormat != null) {
            tableFormat.updateTableFormat();
        }

        refreshTableData();
        repaint();
    }

    private void refreshTableData() {
        if (!(getModel() instanceof EventTableModel)) {
            return;
        }

        Runnable refresh = new Runnable() {
            public void run() {
                ((EventTableModel<?>) getModel()).fireTableDataChanged();
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            refresh.run();
        } else {
            SwingUtilities.invokeLater(refresh);
        }
    }

    @Override
    public void onThemeChanged() {
        Color tableBackground = UIManager.getColor("Table.background");
        Color tableForeground = UIManager.getColor("Table.foreground");
        Color gridColor = UIManager.getColor("Table.gridColor");

        if (tableBackground != null) {
            setBackground(tableBackground);
        }
        if (tableForeground != null) {
            setForeground(tableForeground);
        }
        if (gridColor != null) {
            setGridColor(gridColor);
        }

        if ((pane != null) && (tableBackground != null)) {
            pane.getViewport().setBackground(tableBackground);
            pane.setBackground(tableBackground);
        }

        refreshTableData();
        revalidate();
        repaint();
    }

    public void cleanup() {
        ThemeWatcher.unregister(this);
    }

}
