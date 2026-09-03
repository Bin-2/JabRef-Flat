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

    public PrefsDialog3(JabRefFrame parent) {
        super(parent, Globals.lang("JabRef preferences"), false);
        final JabRefPreferences prefs = JabRefPreferences.getInstance();
        frame = parent;

        final JList<String> chooser;

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
        //ArrayList<Component> comps = new ArrayList<Component>();
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
                String o = (String) chooser.getSelectedValue();
                cardLayout.show(main, o);
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

            }
        });

        importPrefs.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String filename = FileDialogs.getNewFile(frame, new File(System
                        .getProperty("user.home")), ".xml", JFileChooser.OPEN_DIALOG, false);
                if (filename == null) {
                    return;
                }

                try {
                    prefs.importPreferences(filename);
                    setValues();
                    appearancePrefsTab.applyImportedTheme(frame);
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

    class OkAction extends AbstractAction {

        public OkAction() {
            super("Ok");
        }

        public void actionPerformed(ActionEvent e) {
            final long preferencesStartNanos = System.nanoTime();

            AbstractWorker worker = new AbstractWorker() {
                boolean ready = true;
                long readyToCloseMs;
                long storeSettingsMs;
                long flushMs;
                boolean preferencesChanged = true;
                boolean appearanceOnlyChange = false;
                boolean groupOnlyChange = false;
                Set<String> changedPreferenceEntries;

                public void run() {
                    // First check that all tabs are ready to close:
                    int count = main.getComponentCount();
                    Component[] comps = main.getComponents();
                    long readyStart = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        if (!((PrefsTab) comps[i]).readyToClose()) {
                            ready = false;
                            readyToCloseMs = elapsedMillis(readyStart);
                            return; // If not, break off.
                        }
                    }
                    readyToCloseMs = elapsedMillis(readyStart);

                    // Then store settings and close:
                    long storeStart = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        ((PrefsTab) comps[i]).storeSettings();
                    }
                    storeSettingsMs = elapsedMillis(storeStart);

                    changedPreferenceEntries = changedPreferenceEntriesSinceOpen();
                    preferencesChanged = changedPreferenceEntries == null
                            || !changedPreferenceEntries.isEmpty();
                    appearanceOnlyChange = preferencesChanged
                            && isAppearanceOnlyPreferenceChange(changedPreferenceEntries);
                    groupOnlyChange = preferencesChanged
                            && isGroupOnlyPreferenceChange(changedPreferenceEntries);

                    long flushStart = System.nanoTime();
                    Globals.prefs.flush();
                    flushMs = elapsedMillis(flushStart);
                }

                public void update() {
                    if (!ready) {
                        return;
                    }

                    long updateStart = System.nanoTime();
                    setVisible(false);

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

//                    long renderersMs = 0L;
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
                        long renderersStart = System.nanoTime();
//                        MainTable.updateRenderers();
//                        renderersMs = elapsedMillis(renderersStart);

                        long themeStart = System.nanoTime();
                        ThemeWatcher.notifyThemeChanged();
                        themeMs = elapsedMillis(themeStart);

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

                    long updateMs = elapsedMillis(updateStart);
                    long totalMs = elapsedMillis(preferencesStartNanos);
                    System.out.println("[Preferences timer] changed=true"
                            + ", refresh=" + (appearanceOnlyChange ? "appearance"
                                    : (groupOnlyChange ? "groups" : "full"))
                            + ", keys=" + changedPreferenceKeySummary(changedPreferenceEntries)
                            + ", ready=" + readyToCloseMs + " ms"
                            + ", store=" + storeSettingsMs + " ms"
                            + ", flush=" + flushMs + " ms"
                            //                            + ", renderers=" + renderersMs + " ms"
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
            };
            worker.getWorker().run();
            worker.getCallBack().update();

        }
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
        ThemeColorPalette.clearSemanticColorsPreview();
        boolean themeRestored = appearancePrefsTab.cancelThemePreview(frame);
        if (!themeRestored) {
            ThemeWatcher.notifyThemeChanged();
        }
        setVisible(false);
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
