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

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import net.sf.jabref.export.ExportFormats;
import net.sf.jabref.groups.GroupsPrefsTab;
import net.sf.jabref.gui.FileDialogs;

import com.jgoodies.forms.builder.ButtonBarBuilder;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.WindowConstants;

/**
 * Preferences dialog. Contains a TabbedPane, and tabs will be defined in
 * separate classes. Tabs MUST implement the PrefsTab interface, since this
 * dialog will call the storeSettings() method of all tabs when the user presses
 * ok.
 *
 * With this design, it should be very easy to add new tabs later.
 *
 */
public class PrefsDialog3 extends JDialog {

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1000000L;
    }

    JPanel main;

    JabRefFrame frame;

    private static final Set<String> APPEARANCE_ONLY_PREFERENCE_KEYS
            = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "Theme",
                    "tableColorCodesOn",
                    "fontFamily",
                    "fontStyle",
                    "fontSize",
                    "overrideDefaultFonts",
                    JabRefPreferences.USE_EMBEDDED_MENU_BAR,
                    "tableShowGrid",
                    JabRefPreferences.USE_THEME_SEMANTIC_COLORS,
                    "menuFontSize",
                    "tableRowPadding",
                    "tableBackground",
                    "tableReqFieldBackground",
                    "tableOptFieldBackground",
                    "incompleteEntryBackground",
                    "gridColor",
                    "fieldEditorTextColor",
                    "validFieldBackgroundColor",
                    "activeFieldEditorBackgroundColor",
                    "invalidFieldBackgroundColor",
                    "markedEntryBackground0",
                    "markedEntryBackground1",
                    "markedEntryBackground2",
                    "markedEntryBackground3",
                    "markedEntryBackground4",
                    "markedEntryBackground5"
            )));

    private static final Set<String> GROUP_ONLY_PREFERENCE_KEYS
            = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "groupShowIcons",
                    "groupShowDynamic",
                    "groupExpandTree",
                    "groupsDefaultField",
                    "groupAutoShow",
                    "groupAutoHide",
                    "autoAssignGroup",
                    "groupKeywordSeparator"
            )));

    private Map<String, String> openingPreferencesSnapshot;
    private final AppearancePrefsTab appearancePrefsTab;
    private final JList<String> chooser;
    // Preference operations and dialog state are confined to the EDT.
    private boolean operationInProgress;
    private boolean dialogInputLocked;
    private boolean enabledBeforeSave;
    private Cursor cursorBeforeSave;

    public PrefsDialog3(JabRefFrame parent) {
        super(parent, Globals.lang("JabRef preferences"), false);
        final JabRefPreferences prefs = JabRefPreferences.getInstance();
        frame = parent;

        JButton importPrefs = new JButton(Globals.lang("Import preferences"));
        JButton exportPrefs = new JButton(Globals.lang("Export preferences"));

        main = new JPanel();
        JPanel upper = new JPanel();
        JPanel lower = new JPanel();

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(upper, BorderLayout.CENTER);
        getContentPane().add(lower, BorderLayout.SOUTH);

        final CardLayout cardLayout = new CardLayout();
        main.setLayout(cardLayout);

        // ----------------------------------------------------------------
        // Add tabs to tabbed here. Remember, tabs must implement PrefsTab.
        // ----------------------------------------------------------------
        ArrayList<PrefsTab> tabs = new ArrayList<PrefsTab>();
        tabs.add(new GeneralTab(frame, prefs));
        tabs.add(new NetworkTab(frame, prefs));
        tabs.add(new FileTab(frame, prefs));
        tabs.add(new FileSortTab(frame, prefs));
        tabs.add(new EntryEditorPrefsTab(frame, prefs));
        tabs.add(new GroupsPrefsTab(prefs));
        // tabs.add(new AppearancePrefsTab(frame, prefs));
        appearancePrefsTab = new AppearancePrefsTab(frame, prefs);
        tabs.add(appearancePrefsTab);

        tabs.add(new ExternalTab(frame, this, prefs, parent.helpDiag));
        tabs.add(new TablePrefsTab(prefs, parent));
        tabs.add(new TableColumnsTab(prefs, parent));
        tabs.add(new TabLabelPattern(prefs, parent.helpDiag));
        tabs.add(new PreviewPrefsTab(prefs));
        tabs.add(new NameFormatterTab(parent.helpDiag));
        tabs.add(new ImportSettingsTab());
        tabs.add(new XmpPrefsTab());
        tabs.add(new AdvancedTab(prefs, parent.helpDiag));

        Iterator<PrefsTab> it = tabs.iterator();
        String[] names = new String[tabs.size()];
        int i = 0;
        while (it.hasNext()) {
            PrefsTab tab = it.next();
            names[i++] = tab.getTabName();
            main.add((Component) tab, tab.getTabName());
        }

        upper.setBorder(BorderFactory.createEtchedBorder());

        chooser = new JList<>(names);  // 17:24 2025-09-18

        chooser.setBorder(BorderFactory.createEtchedBorder());
        // Set a prototype value to control the width of the list:
        chooser.setPrototypeCellValue("This should be wide enough");
        chooser.setSelectedIndex(0);
        chooser.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Add the selection listener that will show the correct panel when
        // selection changes:
        chooser.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                String selected = chooser.getSelectedValue();
                if (selected != null) {
                    cardLayout.show(main, selected);
                }
            }
        });

        JPanel one = new JPanel(), two = new JPanel();
        one.setLayout(new BorderLayout());
        two.setLayout(new BorderLayout());
        one.add(chooser, BorderLayout.CENTER);
        one.add(importPrefs, BorderLayout.SOUTH);
        two.add(one, BorderLayout.CENTER);
        two.add(exportPrefs, BorderLayout.SOUTH);
        upper.setLayout(new BorderLayout());
        upper.add(two, BorderLayout.WEST);
        upper.add(main, BorderLayout.CENTER);

        JButton ok = new JButton(Globals.lang("Ok")), cancel = new JButton(Globals.lang("Cancel"));
        ok.addActionListener(new OkAction());
        CancelAction cancelAction = new CancelAction();
        cancel.addActionListener(cancelAction);
        lower.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        ButtonBarBuilder bb = new ButtonBarBuilder(lower);
        bb.addGlue();
        bb.addButton(ok);
        bb.addButton(cancel);
        bb.addGlue();

        // Route every dialog-close path through the same preview rollback.
        Util.bindCloseDialogKeyToCancelAction(this.getRootPane(), cancelAction);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelAndHide();
            }
        });

        // Import and export actions:
        exportPrefs.setToolTipText(Globals.lang("Export preferences to file"));
        importPrefs.setToolTipText(Globals.lang("Import preferences from file"));
        exportPrefs.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!beginOperation()) {
                    return;
                }
                try {
                    String filename = FileDialogs.getNewFile(frame, new File(System
                            .getProperty("user.home")), ".xml", JFileChooser.SAVE_DIALOG, false);
                    if (filename == null) {
                        return;
                    }
                    File file = new File(filename);
                    if (!file.exists()
                            || (JOptionPane.showConfirmDialog(PrefsDialog3.this, "'" + file.getName()
                                    + "' " + Globals.lang("exists. Overwrite file?"),
                                    Globals.lang("Export preferences"), JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)) {

                        try {
                            prefs.exportPreferences(filename);
                        } catch (IOException ex) {
                            JOptionPane.showMessageDialog(PrefsDialog3.this,
                                    Globals.lang("Could not export preferences")
                                    + ": " + ex.getMessage(), Globals.lang("Export preferences"),
                                    JOptionPane.ERROR_MESSAGE);
                            // ex.printStackTrace();
                        }
                    }

                } finally {
                    finishOperation();
                }
            }
        });

        importPrefs.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!beginOperation()) {
                    return;
                }
                try {
                    String filename = FileDialogs.getNewFile(frame, new File(System
                            .getProperty("user.home")), ".xml", JFileChooser.OPEN_DIALOG, false);
                    if (filename == null) {
                        return;
                    }

                    try {
                        prefs.importPreferences(filename);
                        setValues();
                        appearancePrefsTab.applyImportedTheme(frame);
                        frame.updateMenuBarEmbeddingPreference();
                        BibtexEntryType.loadCustomEntryTypes(prefs);
                        ExportFormats.initAllExports();
                        frame.removeCachedEntryEditors();
                        Globals.prefs.updateEntryEditorTabList();
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(PrefsDialog3.this,
                                Globals.lang("Could not import preferences")
                                + ": " + ex.getMessage(), Globals.lang("Import preferences"),
                                JOptionPane.ERROR_MESSAGE);
                        // ex.printStackTrace();
                    }
                } finally {
                    finishOperation();
                }
            }

        });

        setValues();

        pack(); // setSize(440, 500);

        /**
         * Look through component sizes to find which tab is to blame when the
         * dialog grows too large: for (Component co : comps) {
         * System.out.println(co.getPreferredSize()); }
         */
    }

    /**
     * Capture the persisted preference state at the moment the dialog is
     * opened. This must be called for every opening of the reused preferences
     * dialog.
     */
    public void beginPreferenceSession() {
        openingPreferencesSnapshot = capturePreferencesSnapshot();
        appearancePrefsTab.beginThemePreviewSession();
    }

    private Map<String, String> capturePreferencesSnapshot() {
        Map<String, String> snapshot = new HashMap<String, String>();
        try {
            capturePreferencesNode(Globals.prefs.prefs, snapshot);
            return snapshot;
        } catch (BackingStoreException ex) {
            Globals.logger("Could not snapshot preferences: " + ex.getLocalizedMessage());
            return null;
        }
    }

    private void capturePreferencesNode(Preferences node, Map<String, String> snapshot)
            throws BackingStoreException {
        String path = node.absolutePath();
        snapshot.put("node:" + path, "");

        for (String key : node.keys()) {
            snapshot.put("key:" + path + "\u0000" + key, node.get(key, null));
        }

        for (String child : node.childrenNames()) {
            capturePreferencesNode(node.node(child), snapshot);
        }
    }

    private Set<String> changedPreferenceEntriesSinceOpen() {
        if (openingPreferencesSnapshot == null) {
            return null;
        }

        Map<String, String> currentSnapshot = capturePreferencesSnapshot();
        if (currentSnapshot == null) {
            return null;
        }

        Set<String> allEntries = new HashSet<String>();
        allEntries.addAll(openingPreferencesSnapshot.keySet());
        allEntries.addAll(currentSnapshot.keySet());

        Set<String> changedEntries = new HashSet<String>();
        for (String entry : allEntries) {
            String oldValue = openingPreferencesSnapshot.get(entry);
            String newValue = currentSnapshot.get(entry);
            if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
                changedEntries.add(entry);
            }
        }
        return changedEntries;
    }

    private boolean isOnlyPreferenceChange(Set<String> changedEntries, Set<String> allowedKeys) {
        if ((changedEntries == null) || changedEntries.isEmpty()) {
            return false;
        }

        String rootKeyPrefix = "key:" + Globals.prefs.prefs.absolutePath() + "\u0000";
        for (String entry : changedEntries) {
            if (!entry.startsWith(rootKeyPrefix)) {
                return false;
            }
            String key = entry.substring(rootKeyPrefix.length());
            if (!allowedKeys.contains(key)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAppearanceOnlyPreferenceChange(Set<String> changedEntries) {
        return isOnlyPreferenceChange(changedEntries, APPEARANCE_ONLY_PREFERENCE_KEYS);
    }

    private boolean isGroupOnlyPreferenceChange(Set<String> changedEntries) {
        return isOnlyPreferenceChange(changedEntries, GROUP_ONLY_PREFERENCE_KEYS);
    }

    private boolean hasPreferenceChanged(Set<String> changedEntries, String key) {
        if (changedEntries == null) {
            return true;
        }
        String rootKeyPrefix = "key:" + Globals.prefs.prefs.absolutePath() + "\u0000";
        return changedEntries.contains(rootKeyPrefix + key);
    }

    private String changedPreferenceKeySummary(Set<String> changedEntries) {
        if (changedEntries == null) {
            return "unknown";
        }
        if (changedEntries.isEmpty()) {
            return "[]";
        }

        String rootKeyPrefix = "key:" + Globals.prefs.prefs.absolutePath() + "\u0000";
        ArrayList<String> keys = new ArrayList<String>();
        for (String entry : changedEntries) {
            if (entry.startsWith(rootKeyPrefix)) {
                keys.add(entry.substring(rootKeyPrefix.length()));
            } else {
                keys.add(entry);
            }
        }
        Collections.sort(keys);
        return keys.toString();
    }

    private boolean beginOperation() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Preference operations must run on the EDT");
        }
        if (operationInProgress || !isVisible() || !isDisplayable()) {
            return false;
        }
        operationInProgress = true;
        return true;
    }

    private void lockDialogInput() {
        enabledBeforeSave = isEnabled();
        cursorBeforeSave = getCursor();
        dialogInputLocked = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // Disable the heavyweight window without changing dependent control states.
        setEnabled(false);
    }

    private void unlockDialogInput() {
        if (dialogInputLocked) {
            dialogInputLocked = false;
            setCursor(cursorBeforeSave);
            setEnabled(enabledBeforeSave);
        }
    }

    private void finishOperation() {
        try {
            unlockDialogInput();
        } finally {
            operationInProgress = false;
        }
    }

    private boolean validateTabs(Component[] components) {
        int selectedIndex = chooser.getSelectedIndex();
        for (int i = 0; i < components.length; i++) {
            // Validators display their own dialogs, so reveal the tab first.
            chooser.setSelectedIndex(i);
            if (!((PrefsTab) components[i]).readyToClose()) {
                return false;
            }
        }
        chooser.setSelectedIndex(selectedIndex);
        return true;
    }

    private void showPreferenceError(String message, Throwable error) {
        Globals.logger(message + " " + error);
        error.printStackTrace();
        unlockDialogInput();
        if (isVisible() && isDisplayable() && frame.isDisplayable()) {
            frame.output(Globals.lang("Preferences operation failed."));
            String detail = error.getLocalizedMessage();
            JOptionPane.showMessageDialog(this,
                    message + (detail == null ? "" : "\n" + detail),
                    Globals.lang("JabRef preferences"), JOptionPane.ERROR_MESSAGE);
            if (isVisible() && isDisplayable() && frame.isDisplayable()) {
                requestFocus();
            }
        }
    }

    class OkAction extends AbstractAction {

        public OkAction() {
            super("Ok");
        }

        public void actionPerformed(ActionEvent e) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> actionPerformed(e));
                return;
            }
            if (!beginOperation()) {
                return;
            }

            final long preferencesStartNanos = System.nanoTime();
            boolean handedOff = false;
            boolean storing = false;
            try {
                Component[] components = main.getComponents();
                long readyStart = System.nanoTime();
                if (!validateTabs(components)) {
                    return;
                }
                final long readyToCloseMs = elapsedMillis(readyStart);

                storing = true;
                long storeStart = System.nanoTime();
                for (Component component : components) {
                    ((PrefsTab) component).storeSettings();
                }
                final long storeSettingsMs = elapsedMillis(storeStart);
                final Set<String> changedPreferenceEntries = changedPreferenceEntriesSinceOpen();

                lockDialogInput();
                frame.output(Globals.lang("Saving preferences..."));
                new SwingWorker<Long, Void>() {
                    @Override
                    protected Long doInBackground() throws BackingStoreException, IOException {
                        long flushStart = System.nanoTime();
                        Globals.prefs.flushOrThrow();
                        return elapsedMillis(flushStart);
                    }

                    @Override
                    protected void done() {
                        boolean persisted = false;
                        try {
                            long flushMs = get();
                            persisted = true;
                            if (!isDisplayable() || !frame.isDisplayable()) {
                                return;
                            }
                            applyPreferenceChanges(changedPreferenceEntries, preferencesStartNanos,
                                    readyToCloseMs, storeSettingsMs, flushMs);
                            setVisible(false);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            showPreferenceError(Globals.lang("Saving preferences was interrupted.\nSome changes may already have been applied or saved."), ex);
                        } catch (ExecutionException ex) {
                            showPreferenceError(Globals.lang("Could not save preferences.\nSome changes may already have been applied or saved.\nPlease correct the problem and try again."),
                                    ex.getCause() == null ? ex : ex.getCause());
                        } catch (RuntimeException ex) {
                            showPreferenceError(persisted
                                    ? Globals.lang("Preferences were saved, but the interface could not be fully refreshed.")
                                    : Globals.lang("Could not save preferences.\nSome changes may already have been applied or saved."), ex);
                        } finally {
                            finishOperation();
                        }
                    }
                }.execute();
                handedOff = true;
            } catch (RuntimeException ex) {
                showPreferenceError(storing
                        ? Globals.lang("Could not apply preferences.\nSome changes may already have been applied or saved.")
                        : Globals.lang("Could not validate preferences."), ex);
            } finally {
                if (!handedOff) {
                    finishOperation();
                }
            }
        }
    }

    private void applyPreferenceChanges(Set<String> changedPreferenceEntries, long preferencesStartNanos,
            long readyToCloseMs, long storeSettingsMs, long flushMs) {
        boolean preferencesChanged = changedPreferenceEntries == null
                || !changedPreferenceEntries.isEmpty();
        boolean appearanceOnlyChange = preferencesChanged
                && isAppearanceOnlyPreferenceChange(changedPreferenceEntries);
        boolean groupOnlyChange = preferencesChanged
                && isGroupOnlyPreferenceChange(changedPreferenceEntries);
        boolean menuBarEmbeddingChanged = preferencesChanged
                && hasPreferenceChanged(changedPreferenceEntries, JabRefPreferences.USE_EMBEDDED_MENU_BAR);
        boolean legacyExternalProgramsChanged = preferencesChanged
                && hasPreferenceChanged(changedPreferenceEntries, JabRefPreferences.SHOW_LEGACY_EXTERNAL_PROGRAMS);

        long updateStart = System.nanoTime();

        if (!preferencesChanged) {
            long updateMs = elapsedMillis(updateStart);
            long totalMs = elapsedMillis(preferencesStartNanos);
            System.out.println("[Preferences timer] changed=false"
                    + ", ready=" + readyToCloseMs + " ms"
                    + ", store=" + storeSettingsMs + " ms"
                    + ", flush=" + flushMs + " ms"
                    + ", uiApply=" + updateMs + " ms"
                    + ", total=" + totalMs + " ms");
            frame.output(Globals.lang("Preferences recorded."));
            return;
        }

        long themeMs = 0L;
        long editorColorsMs = 0L;
        long setupAllTablesMs = 0L;
        long tableAppearanceMs = 0L;
        long groupsMs = 0L;
        long alternateViewerMs = 0L;

        if (groupOnlyChange) {
            long groupsStart = System.nanoTime();
            frame.groupSelector.revalidateGroups();
            groupsMs = elapsedMillis(groupsStart);
        } else {

            long themeStart = System.nanoTime();
            ThemeWatcher.notifyThemeChanged();
            themeMs = elapsedMillis(themeStart);

            if (menuBarEmbeddingChanged) {
                frame.updateMenuBarEmbeddingPreference();
            }

            long editorColorsStart = System.nanoTime();
            GUIGlobals.updateEntryEditorColors();
            editorColorsMs = elapsedMillis(editorColorsStart);

            if (appearanceOnlyChange) {
                long tableAppearanceStart = System.nanoTime();
                frame.updateAllTableAppearancePreferences();
                tableAppearanceMs = elapsedMillis(tableAppearanceStart);
            } else {
                setupAllTablesMs = frame.setupAllTablesWithTiming();
            }

            long groupsStart = System.nanoTime();
            frame.groupSelector.revalidateGroups(); // icons may have
            // changed
            groupsMs = elapsedMillis(groupsStart);

            long alternateViewerStart = System.nanoTime();
            frame.updateAlternatePdfViewerAction();
            alternateViewerMs = elapsedMillis(alternateViewerStart);
        }

        if (legacyExternalProgramsChanged) {
            frame.refreshToolbarIcons();
        }

        long updateMs = elapsedMillis(updateStart);
        long totalMs = elapsedMillis(preferencesStartNanos);
        System.out.println("[Preferences timer] changed=true"
                + ", refresh=" + (appearanceOnlyChange ? "appearance"
                        : (groupOnlyChange ? "groups" : "full"))
                + ", keys=" + changedPreferenceKeySummary(changedPreferenceEntries)
                + ", ready=" + readyToCloseMs + " ms"
                + ", store=" + storeSettingsMs + " ms"
                + ", flush=" + flushMs + " ms"
                + ", themeNotify=" + themeMs + " ms"
                + ", editorColors=" + editorColorsMs + " ms"
                + ", tableAppearance=" + tableAppearanceMs + " ms"
                + ", tables=" + setupAllTablesMs + " ms"
                + ", groups=" + groupsMs + " ms"
                + ", alternateViewer=" + alternateViewerMs + " ms"
                + ", uiApply=" + updateMs + " ms"
                + ", total=" + totalMs + " ms");

        frame.output(Globals.lang("Preferences recorded."));
    }

    public void setValues() {
        // Update all field values in the tabs:
        int count = main.getComponentCount();
        Component[] comps = main.getComponents();
        for (int i = 0; i < count; i++) {
            ((PrefsTab) comps[i]).setValues();
        }
    }

    private void cancelAndHide() {
        if (!beginOperation()) {
            return;
        }
        try {
            ThemeColorPalette.clearSemanticColorsPreview();
            boolean themeRestored = appearancePrefsTab.cancelThemePreview(frame);
            if (!themeRestored) {
                ThemeWatcher.notifyThemeChanged();
            }
            setVisible(false);
        } finally {
            finishOperation();
        }
    }

    class CancelAction extends AbstractAction {

        public CancelAction() {
            super("Cancel");
        }

        public void actionPerformed(ActionEvent e) {
            cancelAndHide();
        }
    }

}
