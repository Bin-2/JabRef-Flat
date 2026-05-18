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

import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;

import javax.swing.AbstractAction;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import net.sf.jabref.BasePanel;
import net.sf.jabref.BibtexEntry;
import net.sf.jabref.EntryEditor;
import net.sf.jabref.FocusRequester;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.PreviewPanel;
import net.sf.jabref.RightClickMenu;
import net.sf.jabref.Util;
import net.sf.jabref.external.ExternalFileMenuItem;
import ca.odell.glazedlists.EventList;
import ca.odell.glazedlists.event.ListEvent;
import ca.odell.glazedlists.event.ListEventListener;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.sf.jabref.specialfields.SpecialField;
import net.sf.jabref.specialfields.SpecialFieldValue;
import net.sf.jabref.specialfields.SpecialFieldsUtils;

/**
 * List event, mouse, key and focus listener for the main table that makes up
 * the most part of the BasePanel for a single bib database.
 */
public class MainTableSelectionListener implements ListEventListener<BibtexEntry>, MouseListener,
        KeyListener, FocusListener {

    PreviewPanel[] previewPanel = null;
    int activePreview = Globals.prefs.getInt("activePreview");
    PreviewPanel preview;
    MainTable table;
    BasePanel panel;
    EventList<BibtexEntry> tableRows;
    private boolean previewActive = Globals.prefs.getBoolean("previewEnabled");
    private volatile boolean workingOnPreview = false;
    private SwingWorker<Void, Void> previewWorker = null;
    private int previewRequestId = 0;

    private boolean enabled = true;

    // Register the last character pressed to quick jump in the table. Together
    // with storing the last row number jumped to, this is used to let multiple
    // key strokes cycle between all entries starting with the same letter:
    private int[] lastPressed = new int[20];
    private int lastPressedCount = 0;
    private long lastPressedTime = 0;
    private long QUICK_JUMP_TIMEOUT = 2000;

    //private int lastCharPressed = -1;
    public MainTableSelectionListener(BasePanel panel, MainTable table) {
        this.table = table;
        this.panel = panel;
        this.tableRows = table.getTableRows();
        instantiatePreviews();
        this.preview = previewPanel[activePreview];
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private void instantiatePreviews() {
        previewPanel = new PreviewPanel[]{
            new PreviewPanel(panel.database(), null, panel, panel.metaData(), Globals.prefs
            .get("preview0"), true),
            new PreviewPanel(panel.database(), null, panel, panel.metaData(), Globals.prefs
            .get("preview1"), true)};

        panel.frame().getSearchManager().addSearchListener(previewPanel[0]);
        panel.frame().getSearchManager().addSearchListener(previewPanel[1]);
    }

    public void updatePreviews() {
        try {
            previewPanel[0].readLayout(Globals.prefs.get("preview0"));
            previewPanel[1].readLayout(Globals.prefs.get("preview1"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void listChanged(ListEvent<BibtexEntry> e) {
        if (!enabled) {
            return;
        }
        EventList<BibtexEntry> selected = e.getSourceList();
        Object newSelected = null;
        while (e.next()) {
            if (e.getType() == ListEvent.INSERT) {
                if (newSelected != null) {
                    return; // More than one new selected. Do nothing.
                } else {
                    if (e.getIndex() < selected.size()) {
                        newSelected = selected.get(e.getIndex());
                    }
                }

            }
        }

        if (newSelected != null) {

            // Ok, we have a single new entry that has been selected. Now decide what to do with it:
            final BibtexEntry toShow = (BibtexEntry) newSelected;
            final int mode = panel.getMode(); // What is the panel already showing?
            if ((mode == BasePanel.WILL_SHOW_EDITOR) || (mode == BasePanel.SHOWING_EDITOR)) {
                // An entry is currently being edited.
                EntryEditor oldEditor = panel.getCurrentEditor();
                String visName = null;
                if (oldEditor != null) {
                    visName = oldEditor.getVisiblePanelName();
                }
                // Get an old or new editor for the entry to edit:
                EntryEditor newEditor = panel.getEntryEditor(toShow);

                if ((oldEditor != null))// && (oldEditor != newEditor))
                {
                    oldEditor.setMovingToDifferentEntry();
                }

                // Show the new editor unless it was already visible:
                if ((newEditor != oldEditor) || (mode != BasePanel.SHOWING_EDITOR)) {

                    if (visName != null) {
                        newEditor.setVisiblePanel(visName);
                    }
                    panel.showEntryEditor(newEditor);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            table.ensureVisible(table.getSelectedRow());
                        }
                    });
                }

            } else {
                // Either nothing or a preview was shown. Update the preview.
                if (previewActive) {
                    updatePreview(toShow, false);
                }

            }
        }

    }

    private void updatePreview(final BibtexEntry toShow, final boolean changedPreview) {
        updatePreview(toShow, changedPreview, 0);
    }

    private void updatePreview(final BibtexEntry toShow, final boolean changedPreview, int repeats) {

        final PreviewPanel previewToUpdate = this.preview;
        final int requestId = ++previewRequestId;

        if ((previewWorker != null) && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }

        EventList<BibtexEntry> list = table.getSelected();
        // Check if the entry to preview is still selected:
        if ((list.size() != 1) || (list.get(0) != toShow)) {
            workingOnPreview = false;
            return;
        }

        final int mode = panel.getMode();
        workingOnPreview = true;

        previewWorker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                if (!isCancelled()) {
                    previewToUpdate.setEntry(toShow);
                }
                return null;
            }

            @Override
            protected void done() {
                if (isCancelled() || (requestId != previewRequestId)) {
                    workingOnPreview = false;
                    return;
                }

                EventList<BibtexEntry> selected = table.getSelected();
                if ((selected.size() != 1) || (selected.get(0) != toShow)) {
                    workingOnPreview = false;
                    return;
                }

                if (changedPreview || (mode == BasePanel.SHOWING_NOTHING)) {
                    panel.showPreview(previewToUpdate);
                    panel.adjustSplitter();
                }
                workingOnPreview = false;
            }
        };
        previewWorker.execute();
    }

    public void editSignalled() {
        if (table.getSelected().size() == 1) {
            editSignalled(table.getSelected().get(0));
        }
    }

    public void editSignalled(BibtexEntry entry) {
        final int mode = panel.getMode();
        EntryEditor editor = panel.getEntryEditor(entry);
        if (mode != BasePanel.SHOWING_EDITOR) {
            panel.showEntryEditor(editor);
            panel.adjustSplitter();
        }
        new FocusRequester(editor);
    }

    public void mouseReleased(MouseEvent e) {
        // First find the column and row on which the user has clicked.
        final int col = table.columnAtPoint(e.getPoint());
        final int row = table.rowAtPoint(e.getPoint());

        if (col < 0) {
            return;
        }

        // Check if the user has clicked on an icon cell to open url or pdf.
        final String[] iconType = table.getIconTypeForColumn(col);

        // Check if the user has right-clicked. If so, open the right-click menu.
        if (e.isPopupTrigger() || (e.getButton() == MouseEvent.BUTTON3)) {
            if (row < 0) {
                return;
            }
            if (iconType == null) {
                processPopupTrigger(e, row);
            } else {
                showIconRightClickMenu(e, row, iconType);
            }
        }
    }

    public void mousePressed(MouseEvent e) {
        // all handling is done in "mouseReleased"
    }

    public void mouseClicked(MouseEvent e) {

        // First find the column on which the user has clicked.
        final int col = table.columnAtPoint(e.getPoint());
        final int row = table.rowAtPoint(e.getPoint());

        if (row < 0 || col < 0) {
            return;
        }

        if (e.getClickCount() == 2) {
            BibtexEntry toShow = tableRows.get(row);
            editSignalled(toShow);
            return;
        }

        // Check if the user has clicked on an icon cell to open url or pdf.
        final String[] iconType = table.getIconTypeForColumn(col);

        // Workaround for Windows. Right-click is not popup trigger on mousePressed, but
        // on mouseReleased. Therefore we need to avoid taking action at this point, because
        // action will be taken when the button is released:
        if (Globals.ON_WIN && (iconType != null) && (e.getButton() != MouseEvent.BUTTON1)) {
            return;
        }

        if (iconType != null) {
            // left click on icon field
            SpecialField field = SpecialFieldsUtils.getSpecialFieldInstanceFromFieldName(iconType[0]);
            if ((e.getClickCount() == 1) && (field != null)) {
                // special field found
                if (field.isSingleValueField()) {
                    // directly execute toggle action instead of showing a menu with one action
                    field.getValues().get(0).getAction(panel.frame()).action();
                } else {
                    JPopupMenu menu = new JPopupMenu();
                    for (SpecialFieldValue val : field.getValues()) {
                        menu.add(val.getMenuAction(panel.frame()));
                    }
                    menu.show(table, e.getX(), e.getY());
                }
                return;
            }

            Object value = table.getValueAt(row, col);
            if (value == null) {
                return; // No icon here, so we do nothing.
            }
            final BibtexEntry entry = tableRows.get(row);

            // Get the icon type. Corresponds to the field name.
            int hasField = -1;
            for (int i = iconType.length - 1; i >= 0; i--) {
                if (entry.getField(iconType[i]) != null) {
                    hasField = i;
                }
            }
            if (hasField == -1) {
                return;
            }
            final String fieldName = iconType[hasField];

            //If this is a file link field with specified file types,
            //we should also pass the types.
            String[] fileTypes = {};
            if (hasField == 0 && iconType[hasField].equals(GUIGlobals.FILE_FIELD) && iconType.length > 1) {
                fileTypes = iconType;
            }
            final List<String> listOfFileTypes = Collections.unmodifiableList(Arrays.asList(fileTypes));

            // Open it now. We do this in a thread, so the program won't freeze during the wait.
            (new Thread() {
                public void run() {
                    panel.output(Globals.lang("External viewer called") + ".");

                    Object link = entry.getField(fieldName);
                    if (link == null) {
                        Globals.logger("Error: no link to " + fieldName + ".");
                        return; // There is an icon, but the field is not set.
                    }

                    {
                        // See if this is a simple file link field, or if it is a file-list
                        // field that can specify a list of links:
                        if (fieldName.equals(GUIGlobals.FILE_FIELD)) {

                            // We use a FileListTableModel to parse the field content:
                            FileListTableModel fileList = new FileListTableModel();
                            fileList.setContent((String) link);

                            FileListEntry flEntry = null;
                            // If there are one or more links of the correct type,
                            // open the first one. Unregistered types, such as MP4,
                            // have a null FileListEntry type, so match them by extension.
                            if (listOfFileTypes.size() > 0) {
                                for (int i = 0; i < fileList.getRowCount(); i++) {
                                    FileListEntry candidate = fileList.getEntry(i);
                                    if (matchesRequestedFileType(candidate, listOfFileTypes)) {
                                        flEntry = candidate;
                                        break;
                                    }
                                }
                            } //If there are no file types specified, consider all files.
                            else {
                                for (int i = 0; i < fileList.getRowCount(); i++) {
                                    FileListEntry candidate = fileList.getEntry(i);
                                    if (isUsableFileListEntry(candidate)) {
                                        flEntry = candidate;
                                        break;
                                    }
                                }
                            }
                            if (flEntry != null) {
                                openFileListEntry(entry, flEntry, "");
                            }
                        } else {
                            openNonFileExternalLink(link, fieldName);

                            /*ExternalFileType type = Globals.prefs.getExternalFileTypeByMimeType("text/html");
                            ExternalFileMenuItem item = new ExternalFileMenuItem
                                    (panel.frame(), entry, "",
                                    (String)link, type.getIcon(),
                                    panel.metaData(), type);
                            boolean success = item.openLink();
                            if (!success) {
                                panel.output(Globals.lang("Unable to open link."));
                            } */
                            //Util.openExternalViewer(panel.metaData(), (String)link, fieldName);
                        }

                    }
                    //catch (IOException ex) {
                    //    panel.output(Globals.lang("Error") + ": " + ex.getMessage());
                    //}
                }

            }).start();
        }
    }

    /**
     * Process general right-click events on the table. Show the table context
     * menu at the position where the user right-clicked.
     *
     * @param e The mouse event defining the popup trigger.
     * @param row The row where the event occured.
     */
    protected void processPopupTrigger(MouseEvent e, int row) {
        int selRow = table.getSelectedRow();
        if (selRow == -1
                ||// (getSelectedRowCount() == 0))
                !table.isRowSelected(table.rowAtPoint(e.getPoint()))) {
            table.setRowSelectionInterval(row, row);
            //panel.updateViewToSelected();
        }
        RightClickMenu rightClickMenu = new RightClickMenu(panel, panel.metaData());
        rightClickMenu.show(table, e.getX(), e.getY());
    }

    /**
     * Process popup trigger events occurring on an icon cell in the table. Show
     * a menu where the user can choose which external resource to open for the
     * entry. If no relevant external resources exist, let the normal popup
     * trigger handler do its thing instead.
     *
     * @param e The mouse event defining this popup trigger.
     * @param row The row where the event occurred.
     * @param iconType A string array containing the resource fields associated
     * with this table cell.
     */
    private void showIconRightClickMenu(MouseEvent e, int row, String[] iconType) {
        BibtexEntry entry = tableRows.get(row);
        JPopupMenu menu = new JPopupMenu();
        boolean showDefaultPopup = true;

        // See if this is a simple file link field, or if it is a file-list
        // field that can specify a list of links:
        if (iconType[0].equals(GUIGlobals.FILE_FIELD)) {
            // We use a FileListTableModel to parse the field content:
            Object o = entry.getField(iconType[0]);
            FileListTableModel fileList = new FileListTableModel();
            fileList.setContent((String) o);
            // If there are one or more links, show one menu item per link:
            for (int i = 0; i < fileList.getRowCount(); i++) {
                FileListEntry flEntry = fileList.getEntry(i);
                if (!isUsableFileListEntry(flEntry)) {
                    continue;
                }

                // If file types are specified, ignore files of other types.
                // Unregistered types have null getType(), so match them by extension.
                if ((iconType.length > 1) && !matchesRequestedFileType(flEntry, iconType, 1)) {
                    continue;
                }

                String description = flEntry.getDescription();
                if ((description == null) || (description.trim().length() == 0)) {
                    description = flEntry.getLink();
                }

                if (flEntry.getType() != null) {
                    menu.add(new ExternalFileMenuItem(panel.frame(), entry, description,
                            flEntry.getLink(), flEntry.getType().getIcon(), panel.metaData(),
                            flEntry.getType()));
                } else {
                    menu.add(createDefaultOpenMenuAction(entry, description, flEntry));
                }
                showDefaultPopup = false;
            }
        } else {
            SpecialField field = SpecialFieldsUtils.getSpecialFieldInstanceFromFieldName(iconType[0]);
            if (field != null) {
//                for (SpecialFieldValue val: field.getValues()) {
//                	menu.add(val.getMenuAction(panel.frame()));
//                }
                // full pop should be shown as left click already shows short popup
                showDefaultPopup = true;
            } else {
                for (String anIconType : iconType) {
                    Object o = entry.getField(anIconType);
                    if (o != null) {
                        menu.add(new ExternalFileMenuItem(panel.frame(), entry, (String) o, (String) o,
                                GUIGlobals.getTableIcon(anIconType).getIcon(),
                                panel.metaData(), anIconType));
                        showDefaultPopup = false;
                    }
                }
            }
        }
        if (showDefaultPopup) {
            processPopupTrigger(e, row);
        } else {
            menu.show(table, e.getX(), e.getY());
        }
    }

    private void openNonFileExternalLink(Object link, String fieldName) {
        if (!(link instanceof String) || (((String) link).trim().length() == 0)) {
            panel.output(Globals.lang("Unable to open link."));
            return;
        }

        try {
            Util.openExternalViewer(panel.metaData(), ((String) link).trim(), fieldName);
        } catch (Throwable ex) {
            panel.output(Globals.lang("Unable to open link."));
            ex.printStackTrace();
        }
    }

    private boolean isUsableFileListEntry(FileListEntry flEntry) {
        return (flEntry != null)
                && (flEntry.getLink() != null)
                && (flEntry.getLink().trim().length() > 0);
    }

    private boolean matchesRequestedFileType(FileListEntry flEntry, List<String> requestedTypes) {
        if ((requestedTypes == null) || requestedTypes.isEmpty()) {
            return true;
        }
        if (!isUsableFileListEntry(flEntry)) {
            return false;
        }

        String configuredType = flEntry.getType() == null ? null : flEntry.getType().toString();
        String extension = getUpperCaseExtension(flEntry.getLink());

        for (String requestedType : requestedTypes) {
            if (requestedType == null) {
                continue;
            }
            if ((configuredType != null) && configuredType.equalsIgnoreCase(requestedType)) {
                return true;
            }
            if ((extension != null) && extension.equalsIgnoreCase(requestedType)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRequestedFileType(FileListEntry flEntry, String[] requestedTypes, int startIndex) {
        if ((requestedTypes == null) || (requestedTypes.length <= startIndex)) {
            return true;
        }
        if (!isUsableFileListEntry(flEntry)) {
            return false;
        }

        String configuredType = flEntry.getType() == null ? null : flEntry.getType().toString();
        String extension = getUpperCaseExtension(flEntry.getLink());

        for (int i = startIndex; i < requestedTypes.length; i++) {
            String requestedType = requestedTypes[i];
            if (requestedType == null) {
                continue;
            }
            if ((configuredType != null) && configuredType.equalsIgnoreCase(requestedType)) {
                return true;
            }
            if ((extension != null) && extension.equalsIgnoreCase(requestedType)) {
                return true;
            }
        }
        return false;
    }

    private String getUpperCaseExtension(String link) {
        if (link == null) {
            return null;
        }

        String clean = link.trim();
        int queryIndex = clean.indexOf('?');
        if (queryIndex >= 0) {
            clean = clean.substring(0, queryIndex);
        }
        int fragmentIndex = clean.indexOf('#');
        if (fragmentIndex >= 0) {
            clean = clean.substring(0, fragmentIndex);
        }

        int slashIndex = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        int dotIndex = clean.lastIndexOf('.');
        if ((dotIndex < 0) || (dotIndex <= slashIndex) || (dotIndex == clean.length() - 1)) {
            return null;
        }
        return clean.substring(dotIndex + 1).toUpperCase();
    }

    private AbstractAction createDefaultOpenMenuAction(final BibtexEntry entry,
            final String description, final FileListEntry flEntry) {
        return new AbstractAction(description) {
            public void actionPerformed(ActionEvent event) {
                new Thread() {
                    public void run() {
                        openFileListEntry(entry, flEntry, description);
                    }
                }.start();
            }
        };
    }

    private void openFileListEntry(BibtexEntry entry, FileListEntry flEntry, String description) {
        if (!isUsableFileListEntry(flEntry)) {
            panel.output(Globals.lang("Unable to open link."));
            return;
        }

        if (flEntry.getType() != null) {
            ExternalFileMenuItem item = new ExternalFileMenuItem(panel.frame(), entry,
                    description == null ? "" : description,
                    flEntry.getLink(), flEntry.getType().getIcon(),
                    panel.metaData(), flEntry.getType());
            boolean success = item.openLink();
            if (!success) {
                panel.output(Globals.lang("Unable to open link."));
            }
            return;
        }

        openWithDefaultSystemApplication(flEntry.getLink());
    }

    private void openWithDefaultSystemApplication(String link) {
        try {
            if ((link == null) || (link.trim().length() == 0)) {
                panel.output(Globals.lang("Unable to open link."));
                return;
            }

            String trimmedLink = link.trim();
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;

            if (looksLikeWebUri(trimmedLink) || looksLikeWebUriWithBrokenSlashes(trimmedLink)) {
                String webLink = normalizeWebUriSlashes(trimmedLink);
                if ((desktop != null) && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(webLink));
                    panel.output(Globals.lang("External viewer called") + ".");
                    return;
                }
                Util.openExternalViewer(panel.metaData(), webLink, "url");
                panel.output(Globals.lang("External viewer called") + ".");
                return;
            }

            File file = resolveLinkedFile(trimmedLink);
            if ((desktop != null) && desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file);
                panel.output(Globals.lang("External viewer called") + ".");
            } else {
                panel.output(Globals.lang("Unable to open link."));
            }
        } catch (Throwable ex) {
            panel.output(Globals.lang("Unable to open link.") + " " + link);
            ex.printStackTrace();
        }
    }

    private boolean looksLikeWebUri(String link) {
        return (link != null) && link.matches("(?i)^(https?|ftp)://.*");
    }

    private boolean looksLikeWebUriWithBrokenSlashes(String link) {
        return (link != null) && link.matches("(?i)^(https?|ftp):\\\\.*");
    }

    private String normalizeWebUriSlashes(String link) {
        int colonIndex = link.indexOf(':');
        if (colonIndex <= 0) {
            return link;
        }

        String scheme = link.substring(0, colonIndex).toLowerCase();
        if (!("http".equals(scheme) || "https".equals(scheme) || "ftp".equals(scheme))) {
            return link;
        }

        String rest = link.substring(colonIndex + 1).replace('\\', '/');
        while (rest.startsWith("///")) {
            rest = rest.substring(1);
        }
        if (rest.startsWith("//")) {
            return scheme + ":" + rest;
        }
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        return scheme + "://" + rest;
    }

    private File resolveLinkedFile(String link) {
        File expanded = Util.expandFilename(panel.metaData(), link);
        if (expanded != null) {
            return expanded;
        }

        File file = new File(link);
        if (file.isAbsolute()) {
            return file;
        }

        String[] directories = panel.metaData().getFileDirectory(GUIGlobals.FILE_FIELD);
        if (directories != null) {
            for (String directory : directories) {
                if ((directory == null) || (directory.trim().length() == 0)) {
                    continue;
                }

                File candidate = new File(directory, link);
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }

        return file;
    }

    public void entryEditorClosing(EntryEditor editor) {
        preview.setEntry(editor.getEntry());
        if (previewActive) {
            panel.showPreview(preview);
        } else {
            panel.hideBottomComponent();
        }
        panel.adjustSplitter();
        new FocusRequester(table);
    }

    public void mouseEntered(MouseEvent e) {

    }

    public void mouseExited(MouseEvent e) {

    }

    public void setPreviewActive(boolean enabled) {
        previewActive = enabled;
        if (!previewActive) {
            panel.hideBottomComponent();
        } else {
            if (table.getSelected().size() > 0) {
                updatePreview(table.getSelected().get(0), false);
            }
        }
    }

    public void switchPreview() {
        if (activePreview < previewPanel.length - 1) {
            activePreview++;
        } else {
            activePreview = 0;
        }
        Globals.prefs.putInt("activePreview", activePreview);
        if (previewActive) {
            this.preview = previewPanel[activePreview];

            if (table.getSelected().size() > 0) {
                updatePreview(table.getSelected().get(0), true);
            }
        }
    }

    /**
     * Receive key event on the main table. If the key is a letter or a digit,
     * we should select the first entry in the table which starts with the given
     * letter in the column by which the table is sorted.
     *
     * @param e The KeyEvent
     */
    public void keyTyped(KeyEvent e) {
        if ((!e.isActionKey()) && Character.isLetterOrDigit(e.getKeyChar())
                //&& !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                && (e.getModifiers() == 0)) {
            long time = System.currentTimeMillis();
            if (time - lastPressedTime > QUICK_JUMP_TIMEOUT) {
                lastPressedCount = 0; // Reset last pressed character
            }            // Update timestamp:
            lastPressedTime = time;
            // Add the new char to the search array:
            int c = e.getKeyChar();
            if (lastPressedCount < lastPressed.length) {
                lastPressed[lastPressedCount++] = c;
            }

            int sortingColumn = table.getSortingColumn(0);
            if (sortingColumn == -1) {
                return; // No sorting? TODO: look up by author, etc.?
            }            // TODO: the following lookup should be done by a faster algorithm,
            // such as binary search. But the table may not be sorted properly,
            // due to marked entries, search etc., which rules out the binary search.
            int startRow = 0;
            /*if ((c == lastPressed) && (lastQuickJumpRow >= 0)) {
                if (lastQuickJumpRow < table.getRowCount()-1)
                    startRow = lastQuickJumpRow+1;
            }*/

            boolean done = false;
            while (!done) {
                for (int i = startRow; i < table.getRowCount(); i++) {
                    Object o = table.getValueAt(i, sortingColumn);
                    if (o == null) {
                        continue;
                    }
                    String s = o.toString().toLowerCase();
                    if (s.length() >= lastPressedCount) {
                        for (int j = 0; j < lastPressedCount; j++) {
                            if (s.charAt(j) != lastPressed[j]) {
                                break; // Escape the loop immediately when we find a mismatch
                            } else if (j == lastPressedCount - 1) {
                                // We found a match:
                                table.setRowSelectionInterval(i, i);
                                table.ensureVisible(i);
                                return;
                            }
                        }
                    }
                    //if ((s.length() >= 1) && (s.charAt(0) == c)) {
                    //}
                }
                // Finished, no result. If we didn't start at the beginning of
                // the table, try that. Otherwise, exit the while loop.
                if (startRow > 0) {
                    startRow = 0;
                } else {
                    done = true;
                }

            }

        } else if (e.getKeyChar() == KeyEvent.VK_ESCAPE) {
            lastPressedCount = 0;

        }
    }

    public void keyReleased(KeyEvent e) {
    }

    public void keyPressed(KeyEvent e) {
    }

    public void focusGained(FocusEvent e) {

    }

    public void focusLost(FocusEvent e) {
        lastPressedCount = 0; // Reset quick jump when focus is lost.
    }
}
