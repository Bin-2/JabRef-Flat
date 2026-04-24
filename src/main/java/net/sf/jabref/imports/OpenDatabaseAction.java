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
package net.sf.jabref.imports;

import java.awt.event.ActionEvent;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import net.sf.jabref.*;

import net.sf.jabref.export.AutoSaveManager;
import net.sf.jabref.export.SaveSession;
import net.sf.jabref.gui.FileDialogs;
import net.sf.jabref.external.FileLinksUpgradeWarning;
import net.sf.jabref.label.HandleDuplicateWarnings;
import net.sf.jabref.specialfields.SpecialFieldsUtils;

// The action concerned with opening an existing database.
public class OpenDatabaseAction extends MnemonicAwareAction {

    private static Logger logger = Logger.getLogger(OpenDatabaseAction.class.toString());

    boolean showDialog;
    private JabRefFrame frame;

    // List of actions that may need to be called after opening the file. Such as
    // upgrade actions etc. that may depend on the JabRef version that wrote the file:
    private static ArrayList<PostOpenAction> postOpenActions
            = new ArrayList<PostOpenAction>();

    static {
        // Add the action for checking for new custom entry types loaded from
        // the bib file:
        postOpenActions.add(new CheckForNewEntryTypesAction());
        // Add the action for the new external file handling system in version 2.3:
        postOpenActions.add(new FileLinksUpgradeWarning());
        // Add the action for warning about and handling duplicate BibTeX keys:
        postOpenActions.add(new HandleDuplicateWarnings());
    }

    public OpenDatabaseAction(JabRefFrame frame, boolean showDialog) {
        super(GUIGlobals.getImage("open"));
        this.frame = frame;
        this.showDialog = showDialog;
        putValue(NAME, "Open database");
        putValue(ACCELERATOR_KEY, Globals.prefs.getKey("Open database"));
        putValue(SHORT_DESCRIPTION, Globals.lang("Open BibTeX database"));
    }

    public void actionPerformed(ActionEvent e) {
        List<File> filesToOpen = new ArrayList<File>();
        //File fileToOpen = null;

        if (showDialog) {

            String[] chosen = FileDialogs.getMultipleFiles(frame, new File(Globals.prefs.get("workingDirectory")), ".bib",
                    true);
            if (chosen != null) {
                for (String aChosen : chosen) {
                    if (aChosen != null) {
                        filesToOpen.add(new File(aChosen));
                    }
                }
            }

            /*
            String chosenFile = Globals.getNewFile(frame,
                    new File(Globals.prefs.get("workingDirectory")), ".bib",
                    JFileChooser.OPEN_DIALOG, true);

            if (chosenFile != null) {
                fileToOpen = new File(chosenFile);
            }*/
        } else {
            Util.pr(NAME);
            Util.pr(e.getActionCommand());
            filesToOpen.add(new File(Util.checkName(e.getActionCommand())));
        }

        BasePanel toRaise = null;
        int initialCount = filesToOpen.size(), removed = 0;

        // Check if any of the files are already open:
        for (Iterator<File> iterator = filesToOpen.iterator(); iterator.hasNext();) {
            File file = iterator.next();
            for (int i = 0; i < frame.getTabbedPane().getTabCount(); i++) {
                BasePanel bp = frame.baseAt(i);
                if ((bp.getFile() != null) && bp.getFile().equals(file)) {
                    iterator.remove();
                    removed++;
                    // See if we removed the final one. If so, we must perhaps
                    // raise the BasePanel in question:
                    if (removed == initialCount) {
                        toRaise = bp;
                    }
                    break;
                }
            }
        }

        // Run the actual open in a thread to prevent the program
        // locking until the file is loaded.
        if (filesToOpen.size() > 0) {
            final List<File> theFiles = Collections.unmodifiableList(filesToOpen);
            (new Thread() {
                public void run() {
                    for (File theFile : theFiles) {
                        openIt(theFile, true);
                    }

                }
            }).start();
            for (File theFile : theFiles) {
                frame.getFileHistory().newFile(theFile.getPath());
            }
        } // If no files are remaining to open, this could mean that a file was
        // already open. If so, we may have to raise the correct tab:
        else if (toRaise != null) {
            frame.output(Globals.lang("File '%0' is already open.", toRaise.getFile().getPath()));
            frame.getTabbedPane().setSelectedComponent(toRaise);
        }
    }

    class OpenItSwingHelper implements Runnable {

        BasePanel bp;
        boolean raisePanel;
        File file;

        OpenItSwingHelper(BasePanel bp, File file, boolean raisePanel) {
            this.bp = bp;
            this.raisePanel = raisePanel;
            this.file = file;
        }

        public void run() {
            frame.addTab(bp, file, raisePanel);

        }
    }

    public void openIt(File file, boolean raisePanel) {
        long totalStartNanos = System.nanoTime();

        if ((file != null) && (file.exists())) {
            File fileToLoad = file;
            frame.output(Globals.lang("Opening") + ": '" + file.getPath() + "'");
            boolean tryingAutosave = false;
            boolean autoSaveFound = AutoSaveManager.newerAutoSaveExists(file);

            if (autoSaveFound && !Globals.prefs.getBoolean("promptBeforeUsingAutosave")) {
                fileToLoad = AutoSaveManager.getAutoSaveFile(file);
                tryingAutosave = true;
            } else if (autoSaveFound) {
                int answer = JOptionPane.showConfirmDialog(null, "<html>"
                        + Globals.lang("An autosave file was found for this database. This could indicate ")
                        + Globals.lang("that JabRef didn't shut down cleanly last time the file was used.") + "<br>"
                        + Globals.lang("Do you want to recover the database from the autosave file?") + "</html>",
                        Globals.lang("Recover from autosave"), JOptionPane.YES_NO_OPTION);
                if (answer == JOptionPane.YES_OPTION) {
                    fileToLoad = AutoSaveManager.getAutoSaveFile(file);
                    tryingAutosave = true;
                }
            }

            boolean done = false;
            while (!done) {
                String fileName = file.getPath();
                Globals.prefs.put("workingDirectory", file.getPath());
                String encoding = Globals.prefs.get("defaultEncoding");

                if (Util.hasLockFile(file)) {
                    long modTime = Util.getLockFileTimeStamp(file);
                    if ((modTime != -1) && (System.currentTimeMillis() - modTime
                            > SaveSession.LOCKFILE_CRITICAL_AGE)) {
                        int answer = JOptionPane.showConfirmDialog(null, "<html>" + Globals.lang("Error opening file")
                                + " '" + fileName + "'. " + Globals.lang("File is locked by another JabRef instance.")
                                + "<p>" + Globals.lang("Do you want to override the file lock?"),
                                Globals.lang("File locked"), JOptionPane.YES_NO_OPTION);
                        if (answer == JOptionPane.YES_OPTION) {
                            Util.deleteLockFile(file);
                        } else {
                            return;
                        }
                    } else if (!Util.waitForFileLock(file, 10)) {
                        JOptionPane.showMessageDialog(null, Globals.lang("Error opening file")
                                + " '" + fileName + "'. " + Globals.lang("File is locked by another JabRef instance."),
                                Globals.lang("Error"), JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }

                ParserResult pr;
                String errorMessage = null;
                long loadStartNanos = System.nanoTime();

                try {
                    pr = loadDatabase(fileToLoad, encoding);
                } catch (Exception ex) {
                    errorMessage = ex.getMessage();
                    pr = null;
                } finally {
                    double loadSeconds = (System.nanoTime() - loadStartNanos) / 1_000_000_000.0;
                    frame.output(Globals.lang("Load time") + ": "
                            + String.format(java.util.Locale.ROOT, "%.3f s", loadSeconds)
                            + " (" + fileToLoad.getPath() + ")");
                }

                if ((pr == null) || (pr == ParserResult.INVALID_FORMAT)) {
                    JOptionPane.showMessageDialog(null, Globals.lang("Error opening file") + " '" + fileName + "'",
                            Globals.lang("Error"),
                            JOptionPane.ERROR_MESSAGE);

                    String message = "<html>" + errorMessage + "<p>"
                            + (tryingAutosave ? Globals.lang("Error opening autosave of '%0'. Trying to load '%0' instead.", file.getName())
                                    : "") + "</html>";
                    JOptionPane.showMessageDialog(null, message, Globals.lang("Error opening file"), JOptionPane.ERROR_MESSAGE);

                    if (tryingAutosave) {
                        tryingAutosave = false;
                        fileToLoad = file;
                    } else {
                        done = true;
                    }
                    continue;
                } else {
                    done = true;
                }

                final BasePanel panel = addNewDatabase(pr, file, raisePanel);
                if (tryingAutosave) {
                    panel.markNonUndoableBaseChanged();
                }

                final ParserResult prf = pr;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        performPostOpenActions(panel, prf, true);
                    }
                });
            }
        }

        double totalSeconds = (System.nanoTime() - totalStartNanos) / 1_000_000_000.0;
        frame.output(Globals.lang("Total open time") + ": "
                + String.format(java.util.Locale.ROOT, "%.3f s", totalSeconds)
                + " (" + file.getPath() + ")");
    }

    /**
     * Go through the list of post open actions, and perform those that need to
     * be performed.
     *
     * @param panel The BasePanel where the database is shown.
     * @param pr The result of the bib file parse operation.
     */
    public static void performPostOpenActions(BasePanel panel, ParserResult pr,
            boolean mustRaisePanel) {
        for (PostOpenAction action : postOpenActions) {
            if (action.isActionNecessary(pr)) {
                if (mustRaisePanel) {
                    panel.frame().getTabbedPane().setSelectedComponent(panel);
                }
                action.performAction(panel, pr);
            }
        }
    }

    public BasePanel addNewDatabase(ParserResult pr, final File file,
            boolean raisePanel) {

        String fileName = file.getPath();
        BibtexDatabase db = pr.getDatabase();
        MetaData meta = pr.getMetaData();

        if (pr.hasWarnings()) {
            final String[] wrns = pr.warnings();
            (new Thread() {
                public void run() {
                    StringBuffer wrn = new StringBuffer();
                    for (int i = 0; i < wrns.length; i++) {
                        wrn.append(i + 1).append(". ").append(wrns[i]).append("\n");
                    }

                    if (wrn.length() > 0) {
                        wrn.deleteCharAt(wrn.length() - 1);
                    }
                    // Note to self or to someone else: The following line causes an
                    // ArrayIndexOutOfBoundsException in situations with a large number of
                    // warnings; approx. 5000 for the database I opened when I observed the problem
                    // (duplicate key warnings). I don't think this is a big problem for normal situations,
                    // and it may possibly be a bug in the Swing code.
                    JOptionPane.showMessageDialog(frame, wrn.toString(),
                            Globals.lang("Warnings") + " (" + file.getName() + ")",
                            JOptionPane.WARNING_MESSAGE);
                }
            }).start();
        }
        BasePanel bp = new BasePanel(frame, db, file, meta, pr.getEncoding());

        // file is set to null inside the EventDispatcherThread
        SwingUtilities.invokeLater(new OpenItSwingHelper(bp, file, raisePanel));

        frame.output(Globals.lang("Opened database") + " '" + fileName
                + "' " + Globals.lang("with") + " "
                + db.getEntryCount() + " " + Globals.lang("entries") + ".");

        return bp;
    }

    public static ParserResult loadDatabase(File fileToOpen, String encoding)
            throws IOException {

        Reader reader;
        String suppliedEncoding = detectEncodingFromHeader(fileToOpen);

        if (suppliedEncoding != null) {
            try {
                reader = ImportFormatReader.getReader(fileToOpen, suppliedEncoding);
                encoding = suppliedEncoding;
            } catch (Exception ex) {
                reader = ImportFormatReader.getReader(fileToOpen, encoding);
            }
        } else {
            reader = ImportFormatReader.getReader(fileToOpen, encoding);
        }

        BibtexParser bp = new BibtexParser(reader);

        ParserResult pr = bp.parse();
        pr.setEncoding(encoding);
        pr.setFile(fileToOpen);

        if (SpecialFieldsUtils.keywordSyncEnabled()) {
            for (BibtexEntry entry : pr.getDatabase().getEntries()) {
                if (entry.getField("keywords") != null) {
                    SpecialFieldsUtils.syncSpecialFieldsFromKeywords(entry, null);
                }
            }
            logger.fine(Globals.lang("Synchronized special fields based on keywords"));
        }

        if (!pr.getMetaData().isGroupTreeValid()) {
            pr.addWarning(Globals.lang("Group tree could not be parsed. If you save the BibTeX database, all groups will be lost."));
        }

        return pr;
    }

    private static String detectEncodingFromHeader(File file) throws IOException {
        final int maxBytes = 8192;
        byte[] buffer = new byte[maxBytes];
        int len;

        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            len = in.read(buffer);
        } finally {
            in.close();
        }

        if (len <= 0) {
            return null;
        }

        String detected = detectEncodingFromDecodedPrefix(buffer, len, "UTF8");
        if (detected != null) {
            return detected;
        }

        return detectEncodingFromDecodedPrefix(buffer, len, "UTF-16");
    }

    private static String detectEncodingFromDecodedPrefix(byte[] buffer, int len, String charsetName) {
        try {
            String prefix = new String(buffer, 0, len, Charset.forName(charsetName));

            int sigIndex = prefix.indexOf(GUIGlobals.SIGNATURE);
            if (sigIndex < 0) {
                return null;
            }

            int encIndex = prefix.indexOf(GUIGlobals.encPrefix, sigIndex + GUIGlobals.SIGNATURE.length());
            if (encIndex < 0) {
                return null;
            }

            int valueStart = encIndex + GUIGlobals.encPrefix.length();
            int valueEnd = valueStart;

            while (valueEnd < prefix.length()) {
                char ch = prefix.charAt(valueEnd);
                if (ch == '\r' || ch == '\n') {
                    break;
                }
                valueEnd++;
            }

            String result = prefix.substring(valueStart, valueEnd).trim();
            return result.length() > 0 ? result : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
