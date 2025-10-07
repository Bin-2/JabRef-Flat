/*  Copyright (C) 2003-2015 JabRef contributors.
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

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.SortedSet;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.sf.jabref.export.*;
import net.sf.jabref.external.ExternalFileTypeEditor;
import net.sf.jabref.external.PushToApplicationButton;
import net.sf.jabref.groups.EntryTableTransferHandler;
import net.sf.jabref.groups.GroupSelector;
import net.sf.jabref.gui.*;
import net.sf.jabref.gui.menus.help.ForkMeOnGitHubAction;
import net.sf.jabref.help.HelpAction;
import net.sf.jabref.help.HelpDialog;
import net.sf.jabref.imports.EntryFetcher;
import net.sf.jabref.imports.GeneralFetcher;
import net.sf.jabref.imports.ImportCustomizationDialog;
import net.sf.jabref.imports.ImportFormat;
import net.sf.jabref.imports.ImportFormats;
import net.sf.jabref.imports.ImportMenuItem;
import net.sf.jabref.imports.OpenDatabaseAction;
import net.sf.jabref.imports.ParserResult;
import net.sf.jabref.journals.ManageJournalsAction;
import net.sf.jabref.label.ArticleLabelRule;
import net.sf.jabref.label.BookLabelRule;
import net.sf.jabref.label.IncollectionLabelRule;
import net.sf.jabref.label.InproceedingsLabelRule;
import net.sf.jabref.label.LabelMaker;
// import net.sf.jabref.oo.OpenOfficePanel;
import net.sf.jabref.plugin.PluginCore;
import net.sf.jabref.plugin.PluginInstallerAction;
import net.sf.jabref.plugin.core.JabRefPlugin;
import net.sf.jabref.plugin.core.generated._JabRefPlugin.EntryFetcherExtension;
import net.sf.jabref.specialfields.Printed;
import net.sf.jabref.specialfields.Priority;
import net.sf.jabref.specialfields.Quality;
import net.sf.jabref.specialfields.Rank;
import net.sf.jabref.specialfields.ReadStatus;
import net.sf.jabref.specialfields.Relevance;
import net.sf.jabref.specialfields.SpecialFieldsUtils;
import net.sf.jabref.sql.importer.DbImportAction;
import net.sf.jabref.undo.NamedCompound;
import net.sf.jabref.undo.UndoableInsertEntry;
import net.sf.jabref.undo.UndoableRemoveEntry;
import net.sf.jabref.util.ManageKeywordsAction;
import net.sf.jabref.util.MassSetFieldAction;
import net.sf.jabref.wizard.auximport.gui.FromAuxDialog;
import net.sf.jabref.wizard.integrity.gui.IntegrityWizard;

import com.jgoodies.looks.HeaderStyle;
import com.jgoodies.looks.Options;
import com.jgoodies.uif_lite.component.UIFSplitPane;
import java.awt.event.ActionListener;
import java.util.Map;
import java.util.Set;

/**
 * The main window of the application.
 */
public final class JabRefFrame extends JFrame implements OutputPrinter {

    UIFSplitPane contentPane = new UIFSplitPane();

    JabRefPreferences prefs = Globals.prefs;
    PrefsDialog3 prefsDialog = null;

    private int lastTabbedPanelSelectionIndex = -1;

    // The sidepane manager takes care of populating the sidepane. 
    public SidePaneManager sidePaneManager;

//    private String theme = Globals.prefs.get("Theme");
//    public void setTheme(String theme) {
//        theme = theme;
//    }
//    public String getTheme() {
//        return theme;
//    }
    // tabbed pane color
    Color active = UIManager.getColor("TabbedPane.selectedForeground");
    Color inactive = UIManager.getColor("TabbedPane.foreground");

    JTabbedPane tabbedPane; // initialized at constructor

    final Insets marg = new Insets(1, 0, 2, 0);

    class ToolBar extends JToolBar {

        void addAction(Action a) {
            JButton b = new JButton(a);
            b.setText(null);
            if (!Globals.ON_MAC) {
                b.setMargin(marg);
            }
            add(b);
        }
    }
    ToolBar tlb = new ToolBar();

    JMenuBar mb = new JMenuBar();
    JMenu pluginMenu = subMenu("Plugins");
    boolean addedToPluginMenu = false;

    GridBagLayout gbl = new GridBagLayout();
    GridBagConstraints con = new GridBagConstraints();

    JLabel statusLine = new JLabel("", SwingConstants.LEFT), statusLabel = new JLabel(
            Globals.lang("Status")
            + ":", SwingConstants.LEFT);

    JProgressBar progressBar = new JProgressBar();

    private FileHistory fileHistory = new FileHistory(prefs, this);

    private SysTray sysTray = null;

    LabelMaker labelMaker;

    // The help window.
    public HelpDialog helpDiag = new HelpDialog(this);

    // Here we instantiate menu/toolbar actions. Actions regarding
    // the currently open database are defined as a GeneralAction
    // with a unique command string. This causes the appropriate
    // BasePanel's runCommand() method to be called with that command.
    // Note: GeneralAction's constructor automatically gets translations
    // for the name and message strings.

    /* References to the toggle buttons in the toolbar */
    // the groups interface
    public JToggleButton groupToggle;
    public JToggleButton searchToggle, previewToggle, highlightAny, highlightAll;

    OpenDatabaseAction open = new OpenDatabaseAction(this, true);
    AbstractAction close = new CloseDatabaseAction(),
            quit = new CloseAction(),
            selectKeys = new SelectKeysAction(),
            newDatabaseAction = new NewDatabaseAction(),
            newSubDatabaseAction = new NewSubDatabaseAction(),
            integrityCheckAction = new IntegrityCheckAction(),
            forkMeOnGitHubAction = new ForkMeOnGitHubAction(),
            help = new HelpAction("JabRef help", helpDiag,
                    GUIGlobals.baseFrameHelp, Globals.lang("JabRef help"),
                    prefs.getKey("Help")),
            contents = new HelpAction("Help contents", helpDiag,
                    GUIGlobals.helpContents, Globals.lang("Help contents"),
                    GUIGlobals.getIconUrl("helpContents")),
            about = new HelpAction("About JabRef", helpDiag,
                    GUIGlobals.aboutPage, Globals.lang("About JabRef"),
                    GUIGlobals.getIconUrl("about")),
            editEntry = new GeneralAction("edit", "Edit entry",
                    Globals.lang("Edit entry"),
                    prefs.getKey("Edit entry")),
            focusTable = new GeneralAction("focusTable", "Focus entry table",
                    Globals.lang("Move the keyboard focus to the entry table"),
                    prefs.getKey("Focus entry table")),
            save = new GeneralAction("save", "Save database",
                    Globals.lang("Save database"),
                    prefs.getKey("Save database")),
            saveAs = new GeneralAction("saveAs", "Save database as ...",
                    Globals.lang("Save database as ..."),
                    prefs.getKey("Save database as ...")),
            saveAll = new SaveAllAction(JabRefFrame.this),
            saveSelectedAs = new GeneralAction("saveSelectedAs",
                    "Save selected as ...",
                    Globals.lang("Save selected as ..."),
                    GUIGlobals.getIconUrl("saveAs")),
            saveSelectedAsPlain = new GeneralAction("saveSelectedAsPlain",
                    "Save selected as plain BibTeX ...",
                    Globals.lang("Save selected as plain BibTeX ..."),
                    GUIGlobals.getIconUrl("saveAs")),
            exportAll = ExportFormats.getExportAction(this, false),
            exportSelected = ExportFormats.getExportAction(this, true),
            importCurrent = ImportFormats.getImportAction(this, false),
            importNew = ImportFormats.getImportAction(this, true),
            nextTab = new ChangeTabAction(true),
            prevTab = new ChangeTabAction(false),
            sortTabs = new SortTabsAction(this),
            undo = new GeneralAction("undo", "Undo", Globals.lang("Undo"),
                    prefs.getKey("Undo")),
            redo = new GeneralAction("redo", "Redo", Globals.lang("Redo"),
                    prefs.getKey("Redo")),
            forward = new GeneralAction("forward", "Forward", Globals.lang("Forward"),
                    "right", prefs.getKey("Forward")),
            back = new GeneralAction("back", "Back", Globals.lang("Back"),
                    "left", prefs.getKey("Back")),
            delete = new GeneralAction("delete", "Delete", Globals.lang("Delete"),
                    prefs.getKey("Delete")),
            copy = new EditAction("copy", GUIGlobals.getIconUrl("copy")),
            paste = new EditAction("paste", GUIGlobals.getIconUrl("paste")),
            cut = new EditAction("cut", GUIGlobals.getIconUrl("cut")),
            mark = new GeneralAction("markEntries", "Mark entries",
                    Globals.lang("Mark entries"),
                    prefs.getKey("Mark entries")),
            unmark = new GeneralAction("unmarkEntries", "Unmark entries",
                    Globals.lang("Unmark entries"),
                    prefs.getKey("Unmark entries")),
            unmarkAll = new GeneralAction("unmarkAll", "Unmark all"),
            toggleRelevance = new GeneralAction(
                    Relevance.getInstance().getValues().get(0).getActionName(),
                    Relevance.getInstance().getValues().get(0).getMenuString(),
                    Relevance.getInstance().getValues().get(0).getToolTipText()),
            toggleQualityAssured = new GeneralAction(
                    Quality.getInstance().getValues().get(0).getActionName(),
                    Quality.getInstance().getValues().get(0).getMenuString(),
                    Quality.getInstance().getValues().get(0).getToolTipText()),
            togglePrinted = new GeneralAction(
                    Printed.getInstance().getValues().get(0).getActionName(),
                    Printed.getInstance().getValues().get(0).getMenuString(),
                    Printed.getInstance().getValues().get(0).getToolTipText()),
            //    	priority = new GeneralAction("setPriority", "Set priority",
            //    			                                            Globals.lang("Set priority")),
            manageSelectors = new GeneralAction("manageSelectors", "Manage content selectors"),
            saveSessionAction = new SaveSessionAction(),
            loadSessionAction = new LoadSessionAction(),
            incrementalSearch = new GeneralAction("incSearch", "Incremental search",
                    Globals.lang("Start incremental search"),
                    prefs.getKey("Incremental search")),
            normalSearch = new GeneralAction("search", "Search", Globals.lang("Search"),
                    prefs.getKey("Search")),
            toggleSearch = new GeneralAction("toggleSearch", "Search", Globals.lang("Toggle search panel")),
            copyKey = new GeneralAction("copyKey", "Copy BibTeX key",
                    prefs.getKey("Copy BibTeX key")),
            //"Put a BibTeX reference to the selected entries on the clipboard",
            copyCiteKey = new GeneralAction("copyCiteKey", "Copy \\cite{BibTeX key}",
                    //"Put a BibTeX reference to the selected entries on the clipboard",
                    prefs.getKey("Copy \\cite{BibTeX key}")),
            copyKeyAndTitle = new GeneralAction("copyKeyAndTitle",
                    "Copy BibTeX key and title",
                    prefs.getKey("Copy BibTeX key and title")),
            mergeDatabaseAction = new GeneralAction("mergeDatabase",
                    "Append database",
                    Globals.lang("Append contents from a BibTeX database into the currently viewed database"),
                    GUIGlobals.getIconUrl("open")),
            selectAll = new GeneralAction("selectAll", "Select all",
                    prefs.getKey("Select all")),
            replaceAll = new GeneralAction("replaceAll", "Replace string",
                    prefs.getKey("Replace string")),
            editPreamble = new GeneralAction("editPreamble", "Edit preamble",
                    Globals.lang("Edit preamble"),
                    prefs.getKey("Edit preamble")),
            editStrings = new GeneralAction("editStrings", "Edit strings",
                    Globals.lang("Edit strings"),
                    prefs.getKey("Edit strings")),
            toggleToolbar = new GeneralAction("toggleToolbar", "Hide/show toolbar",
                    Globals.lang("Hide/show toolbar"),
                    prefs.getKey("Hide/show toolbar")),
            toggleGroups = new GeneralAction("toggleGroups",
                    "Toggle groups interface",
                    Globals.lang("Toggle groups interface"),
                    prefs.getKey("Toggle groups interface")),
            togglePreview = new GeneralAction("togglePreview",
                    "Toggle entry preview",
                    Globals.lang("Toggle entry preview"),
                    prefs.getKey("Toggle entry preview")),
            toggleHighlightAny = new GeneralAction("toggleHighlightGroupsMatchingAny",
                    "Highlight groups matching any selected entry",
                    Globals.lang("Highlight groups matching any selected entry"),
                    GUIGlobals.getIconUrl("groupsHighlightAny")),
            toggleHighlightAll = new GeneralAction("toggleHighlightGroupsMatchingAll",
                    "Highlight groups matching all selected entries",
                    Globals.lang("Highlight groups matching all selected entries"),
                    GUIGlobals.getIconUrl("groupsHighlightAll")),
            switchPreview = new GeneralAction("switchPreview",
                    "Switch preview layout",
                    prefs.getKey("Switch preview layout")),
            makeKeyAction = new GeneralAction("makeKey", "Autogenerate BibTeX keys",
                    Globals.lang("Autogenerate BibTeX keys"),
                    prefs.getKey("Autogenerate BibTeX keys")),
            writeXmpAction = new GeneralAction("writeXMP", "Write XMP-metadata to PDFs",
                    Globals.lang("Will write XMP-metadata to the PDFs linked from selected entries."),
                    prefs.getKey("Write XMP")),
            openFolder = new GeneralAction("openFolder", "Open folder",
                    Globals.lang("Open folder"),
                    prefs.getKey("Open folder")),
            openFile = new GeneralAction("openExternalFile", "Open file",
                    Globals.lang("Open file"),
                    prefs.getKey("Open file")),
            openPdf = new GeneralAction("openFile", "Open PDF or PS",
                    Globals.lang("Open PDF or PS"),
                    prefs.getKey("Open PDF or PS")),
            openUrl = new GeneralAction("openUrl", "Open URL or DOI",
                    Globals.lang("Open URL or DOI"),
                    prefs.getKey("Open URL or DOI")),
            //            openSpires = new GeneralAction("openSpires", "Open SPIRES entry",
            //                    Globals.lang("Open SPIRES entry"),
            //                    prefs.getKey("Open SPIRES entry")),
            /*
	   * It looks like this wasn't being implemented for spires anyway so we
	   * comment it out for now.
	   *
	  openInspire = new GeneralAction("openInspire", "Open INSPIRE entry",
                                          Globals.lang("Open INSPIRE entry"),
                                          prefs.getKey("Open INSPIRE entry")),
             */
            dupliCheck = new GeneralAction("dupliCheck", "Find duplicates"),
            //strictDupliCheck = new GeneralAction("strictDupliCheck", "Find and remove exact duplicates"),
            plainTextImport = new GeneralAction("plainTextImport",
                    "New entry from plain text",
                    prefs.getKey("New from plain text")),
            customExpAction = new CustomizeExportsAction(),
            customImpAction = new CustomizeImportsAction(),
            customFileTypesAction = ExternalFileTypeEditor.getAction(this),
            exportToClipboard = new GeneralAction("exportToClipboard", "Export selected entries to clipboard"),
            //expandEndnoteZip = new ExpandEndnoteFilters(this),
            autoSetPdf = new GeneralAction("autoSetPdf", Globals.lang("Synchronize %0 links", "PDF"), Globals.prefs.getKey("Synchronize PDF")),
            autoSetPs = new GeneralAction("autoSetPs", Globals.lang("Synchronize %0 links", "PS"), Globals.prefs.getKey("Synchronize PS")),
            autoSetFile = new GeneralAction("autoSetFile", Globals.lang("Synchronize file links"), Globals.prefs.getKey("Synchronize files")),
            abbreviateMedline = new GeneralAction("abbreviateMedline", "Abbreviate journal names (MEDLINE)",
                    Globals.lang("Abbreviate journal names of the selected entries (MEDLINE abbreviation)")),
            abbreviateIso = new GeneralAction("abbreviateIso", "Abbreviate journal names (ISO)",
                    Globals.lang("Abbreviate journal names of the selected entries (ISO abbreviation)"),
                    Globals.prefs.getKey("Abbreviate")),
            unabbreviate = new GeneralAction("unabbreviate", "Unabbreviate journal names",
                    Globals.lang("Unabbreviate journal names of the selected entries"),
                    Globals.prefs.getKey("Unabbreviate")),
            manageJournals = new ManageJournalsAction(this),
            databaseProperties = new DatabasePropertiesAction(),
            bibtexKeyPattern = new BibtexKeyPatternAction(),
            errorConsole = Globals.errorConsole.getAction(this),
            test = new GeneralAction("test", "Test"),
            dbConnect = new GeneralAction("dbConnect", "Connect to external SQL database",
                    Globals.lang("Connect to external SQL database"),
                    GUIGlobals.getIconUrl("dbConnect")),
            dbExport = new GeneralAction("dbExport", "Export to external SQL database",
                    Globals.lang("Export to external SQL database"),
                    GUIGlobals.getIconUrl("dbExport")),
            Cleanup = new GeneralAction("Cleanup", "Cleanup entries",
                    Globals.lang("Cleanup entries"),
                    prefs.getKey("Cleanup"),
                    ("cleanupentries")),
            mergeEntries = new GeneralAction("mergeEntries", "Merge entries",
                    Globals.lang("Merge entries"),
                    GUIGlobals.getIconUrl("mergeentries")),
            dbImport = new DbImportAction(this).getAction(),
            //downloadFullText = new GeneralAction("downloadFullText", "Look up full text document",
            //        Globals.lang("Follow DOI or URL link and try to locate PDF full text document")),
            increaseFontSize = new IncreaseTableFontSizeAction(),
            decreseFontSize = new DecreaseTableFontSizeAction(),
            installPlugin = new PluginInstallerAction(this),
            resolveDuplicateKeys = new GeneralAction("resolveDuplicateKeys", "Resolve duplicate BibTeX keys",
                    Globals.lang("Find and remove duplicate BibTeX keys"),
                    prefs.getKey("Resolve duplicate BibTeX keys"));

    MassSetFieldAction massSetField = new MassSetFieldAction(this);
    ManageKeywordsAction manageKeywords = new ManageKeywordsAction(this);

    GeneralAction findUnlinkedFiles = new GeneralAction(
            FindUnlinkedFilesDialog.ACTION_COMMAND,
            FindUnlinkedFilesDialog.ACTION_TITLE,
            FindUnlinkedFilesDialog.ACTION_SHORT_DESCRIPTION,
            FindUnlinkedFilesDialog.ACTION_ICON,
            prefs.getKey(FindUnlinkedFilesDialog.ACTION_KEYBINDING_ACTION)
    );

    AutoLinkFilesAction autoLinkFile = new AutoLinkFilesAction();

    PushToApplicationButton pushExternalButton;

    List<EntryFetcher> fetchers = new LinkedList<>();
    List<Action> fetcherActions = new LinkedList<>();

    private SearchManager2 searchManager;

    public GroupSelector groupSelector;

    // The menus for importing/appending other formats
    JMenu importMenu = subMenu("Import into current database"),
            importNewMenu = subMenu("Import into new database"),
            exportMenu = subMenu("Export"),
            customExportMenu = subMenu("Custom export"),
            newDatabaseMenu = subMenu("New database");

    // Other submenus
    JMenu checkAndFix = subMenu("Legacy tools...");

    // The action for adding a new entry of unspecified type.
    NewEntryAction newEntryAction = new NewEntryAction(prefs.getKey("New entry"));
    NewEntryAction[] newSpecificEntryAction = new NewEntryAction[]{
        new NewEntryAction("article", prefs.getKey("New article")),
        new NewEntryAction("book", prefs.getKey("New book")),
        new NewEntryAction("phdthesis", prefs.getKey("New phdthesis")),
        new NewEntryAction("inbook", prefs.getKey("New inbook")),
        new NewEntryAction("mastersthesis", prefs.getKey("New mastersthesis")),
        new NewEntryAction("proceedings", prefs.getKey("New proceedings")),
        new NewEntryAction("inproceedings"),
        new NewEntryAction("conference"),
        new NewEntryAction("incollection"),
        new NewEntryAction("booklet"),
        new NewEntryAction("manual"),
        new NewEntryAction("techreport"),
        new NewEntryAction("unpublished",
        prefs.getKey("New unpublished")),
        new NewEntryAction("misc"),
        new NewEntryAction("other")
    };

    public JabRefFrame() {
        init();
        updateEnabledState();
    }

    private void init() {
        tabbedPane = new DragDropPopupPane(manageSelectors, databaseProperties, bibtexKeyPattern);

        // Load saved toolbar size preference
        int savedSize;
        try {
            savedSize = Integer.parseInt(Globals.prefs.get("toolbarIconSize",
                    String.valueOf(GUIGlobals.TOOLBAR_ICON_MEDIUM)));
        } catch (NumberFormatException e) {
            savedSize = GUIGlobals.TOOLBAR_ICON_MEDIUM; // Default if invalid
        }
        GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE = savedSize;

        UIManager.put("FileChooser.readOnly", Globals.prefs.getBoolean("filechooserDisableRename"));

        MyGlassPane glassPane = new MyGlassPane();
        setGlassPane(glassPane);
        // glassPane.setVisible(true);

        setTitle(GUIGlobals.frameTitle);

        // Fix the window icon loading with better error handling
        try {
            Image iconImage = GUIGlobals.getImageAsImage("jabrefIcon48");
            if (iconImage != null) {
                setIconImage(iconImage);
            } else {
                System.err.println("Could not load window icon: jabrefIcon48");
                // Fallback: try loading directly from resources
                URL iconUrl = GUIGlobals.class.getResource("/images/crystal_16/JabRef-icon-48.png");
                if (iconUrl != null) {
                    setIconImage(new ImageIcon(iconUrl).getImage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error setting window icon: " + e.getMessage());
            e.printStackTrace();
        }

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if (Globals.ON_MAC) {
                    setState(Frame.ICONIFIED);
                } else {
                    (new CloseAction()).actionPerformed(null);
                }
            }
        });

        initLabelMaker();

        initSidePane();

        initLayout();

        initActions();

        // Show the toolbar if it was visible at last shutdown:
        tlb.setVisible(Globals.prefs.getBoolean("toolbarVisible"));

        setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        if (!prefs.getBoolean("windowMaximised")) {

            int sizeX = prefs.getInt("sizeX");
            int sizeY = prefs.getInt("sizeY");
            int posX = prefs.getInt("posX");
            int posY = prefs.getInt("posY");

            /*
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] gs = ge.getScreenDevices();


        // Get size of each screen
        for (int i=0; i<gs.length; i++) {
            DisplayMode dm = gs[i].getDisplayMode();
            int screenWidth = dm.getWidth();
            int screenHeight = dm.getHeight();
            System.out.println(gs[i].getDefaultConfiguration().getBounds());
        }*/
            //
            // Fix for [ 1738920 ] Windows Position in Multi-Monitor environment
            //
            // Do not put a window outside the screen if the preference values are wrong.
            //
            // Useful reference: http://www.exampledepot.com/egs/java.awt/screen_ScreenSize.html?l=rel
            // googled on forums.java.sun.com graphicsenvironment second screen java
            //
            if (GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length == 1) {

                Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0]
                        .getDefaultConfiguration().getBounds();
                Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();

                // Make sure we are not above or to the left of the screen bounds:
                if (posX < bounds.x) {
                    posX = bounds.x;
                }
                if (posY < bounds.y) {
                    posY = bounds.y;
                }

                int height = (int) dim.getHeight();
                int width = (int) dim.getWidth();

                //if (posX < )
                if (posX + sizeX > width) {
                    if (sizeX <= width) {
                        posX = width - sizeX;
                    } else {
                        posX = prefs.getIntDefault("posX");
                        sizeX = prefs.getIntDefault("sizeX");
                    }
                }

                if (posY + sizeY > height) {
                    if (sizeY <= height) {
                        posY = height - sizeY;
                    } else {
                        posY = prefs.getIntDefault("posY");
                        sizeY = prefs.getIntDefault("sizeY");
                    }
                }
            }
            setBounds(posX, posY, sizeX, sizeY);
        }

        tabbedPane.setBorder(null);
        // tabbedPane.setForeground(GUIGlobals.inActiveTabbed);
        // tabbedPane.setForeground(inactive);

        /*
         * The following state listener makes sure focus is registered with the
         * correct database when the user switches tabs. Without this,
         * cut/paste/copy operations would some times occur in the wrong tab.
         */
        tabbedPane.addChangeListener(new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent e) {
                markActiveBasePanel();

                BasePanel bp = basePanel();
                if (bp != null) {
                    groupToggle.setSelected(sidePaneManager.isComponentVisible("groups"));
                    searchToggle.setSelected(sidePaneManager.isComponentVisible("search"));
                    previewToggle.setSelected(Globals.prefs.getBoolean("previewEnabled"));
                    highlightAny
                            .setSelected(Globals.prefs.getBoolean("highlightGroupsMatchingAny"));
                    highlightAll
                            .setSelected(Globals.prefs.getBoolean("highlightGroupsMatchingAll"));
                    Globals.focusListener.setFocused(bp.mainTable);
                    setWindowTitle();

                    // Update search autocompleter with information for the correct database:
                    bp.updateSearchManager();
                    // Set correct enabled state for Back and Forward actions:
                    bp.setBackAndForwardEnabledState();
                    new FocusRequester(bp.mainTable);
                }
            }
        });

        //Note: The registration of Apple event is at the end of initialization, because
        //if the events happen too early (ie when the window is not initialized yet), the
        //opened (double-clicked) documents are not displayed.
        if (Globals.ON_MAC) {
            try {
                Class<?> macreg = Class.forName("osx.macadapter.MacAdapter");
                Method method = macreg.getMethod("registerMacEvents", JabRefFrame.class);
                method.invoke(macreg.newInstance(), this);
            } catch (Exception e) {
                System.err.println("Exception (" + e.getClass().toString() + "): " + e.getMessage());
            }
        }
    }

    public void setWindowTitle() {
        // Set window title:
        BasePanel bp = basePanel();
        if (bp == null) {
            setTitle(GUIGlobals.frameTitle);
            return;
        }
        String star = bp.isBaseChanged() ? "*" : "";
        if (bp.getFile() != null) {
            setTitle(GUIGlobals.frameTitle + " - " + bp.getFile().getPath() + star);
        } else {
            setTitle(GUIGlobals.frameTitle + " - " + Globals.lang("untitled") + star);
        }
    }

    public void updateTabTitleAfterSave(BasePanel bp) {
        String title;
        if (bp.getFile() == null) {
            title = Globals.lang(GUIGlobals.untitledTitle);
        } else {
            title = bp.getFile().getName();
        }

        // Add asterisk if changed
        if (bp.isBaseChanged()) {
            title = title + "*";
        }

        setTabTitle(bp, title, bp.getFile() != null ? bp.getFile().getAbsolutePath() : null);
    }

    private void initSidePane() {
        sidePaneManager = new SidePaneManager(this);

        Globals.sidePaneManager = this.sidePaneManager;
        Globals.helpDiag = this.helpDiag;

        /*
         * Load fetchers that are plug-in extensions
         */
        JabRefPlugin jabrefPlugin = JabRefPlugin.getInstance(PluginCore.getManager());
        if (jabrefPlugin != null) {
            for (EntryFetcherExtension ext : jabrefPlugin.getEntryFetcherExtensions()) {
                try {
                    EntryFetcher fetcher = ext.getEntryFetcher();
                    if (fetcher != null) {
                        fetchers.add(fetcher);
                    }
                } catch (ClassCastException ex) {
                    PluginCore.getManager().disablePlugin(ext.getDeclaringPlugin().getDescriptor());
                    ex.printStackTrace();
                }
            }
        }

        groupSelector = new GroupSelector(this, sidePaneManager);
        searchManager = new SearchManager2(this, sidePaneManager);

        sidePaneManager.register("groups", groupSelector);
        sidePaneManager.register("search", searchManager);

        // Show the search panel if it was visible at last shutdown:
        if (Globals.prefs.getBoolean("searchPanelVisible")) {
            sidePaneManager.show("search");
        }
    }

    // The MacAdapter calls this method when a ".bib" file has been double-clicked from the Finder.
    public void openAction(String filePath) {
        File file = new File(filePath);

        // Check if the file is already open.
        for (int i = 0; i < this.getTabbedPane().getTabCount(); i++) {
            BasePanel bp = this.baseAt(i);
            if ((bp.getFile() != null) && bp.getFile().equals(file)) {
                //The file is already opened, so just raising its tab.
                this.getTabbedPane().setSelectedComponent(bp);
                return;
            }
        }

        if (file.exists()) {
            // Run the actual open in a thread to prevent the program
            // locking until the file is loaded.
            final File theFile = new File(filePath);
            (new Thread() {
                @Override
                public void run() {
                    open.openIt(theFile, true);
                }
            }).start();
        }
    }

    AboutAction aboutAction = new AboutAction();

    class AboutAction
            extends AbstractAction {

        public AboutAction() {
            super(Globals.lang("About JabRef"));

        }

        @Override
        public void actionPerformed(ActionEvent e) {
            about();
        }
    }

    // General info dialog.  The MacAdapter calls this method when "About"
    // is selected from the application menu.
    public void about() {
        JDialog _about = new JDialog(JabRefFrame.this, Globals.lang("About JabRef"),
                true);
        JEditorPane jp = new JEditorPane();
        JScrollPane sp = new JScrollPane(jp, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        jp.setEditable(false);
        try {
            jp.setPage(GUIGlobals.class.getResource("/help/About.html"));//GUIGlobals.aboutPage);
            // We need a hyperlink listener to be able to switch to the license
            // terms and back.
            jp.addHyperlinkListener(new javax.swing.event.HyperlinkListener() {
                @Override
                public void hyperlinkUpdate(javax.swing.event.HyperlinkEvent e) {
                    if (e.getEventType()
                            == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            ((JEditorPane) e.getSource()).setPage(e.getURL());
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
            _about.getContentPane().add(sp);
            _about.setSize(GUIGlobals.aboutSize);
            Util.placeDialog(_about, JabRefFrame.this);
            _about.setVisible(true);
        } catch (IOException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(JabRefFrame.this, "Could not load file 'About.html'",
                    "Error", JOptionPane.ERROR_MESSAGE);
        }

    }

    // General preferences dialog.  The MacAdapter calls this method when "Preferences..."
    // is selected from the application menu.
    public void preferences() {
        //PrefsDialog.showPrefsDialog(JabRefFrame.this, prefs);
        AbstractWorker worker = new AbstractWorker() {
            @Override
            public void run() {
                output(Globals.lang("Opening preferences..."));
                if (prefsDialog == null) {
                    prefsDialog = new PrefsDialog3(JabRefFrame.this);
                    Util.placeDialog(prefsDialog, JabRefFrame.this);
                } else {
                    prefsDialog.setValues();
                }

            }

            @Override
            public void update() {
                prefsDialog.setVisible(true);
                output("");
            }
        };
        worker.getWorker().run();
        worker.getCallBack().update();
    }

    public JabRefPreferences prefs() {
        return prefs;
    }

    /**
     * Tears down all things started by JabRef
     *
     * FIXME: Currently some threads remain and therefore hinder JabRef to be
     * closed properly
     *
     * @param filenames the file names of all currently opened files - used for
     * storing them if prefs openLastEdited is set to true
     */
    private void tearDownJabRef(List<String> filenames) {
        dispose();

        // Clear fetchers and fetcherActions to prevent memory leaks
        clearFetchersAndActions();

        if (basePanel() != null) {
            basePanel().saveDividerLocation();
        }
        prefs.putInt("posX", JabRefFrame.this.getLocation().x);
        prefs.putInt("posY", JabRefFrame.this.getLocation().y);
        prefs.putInt("sizeX", JabRefFrame.this.getSize().width);
        prefs.putInt("sizeY", JabRefFrame.this.getSize().height);
        //prefs.putBoolean("windowMaximised", (getExtendedState()&MAXIMIZED_BOTH)>0);
        prefs.putBoolean("windowMaximised", (getExtendedState() == Frame.MAXIMIZED_BOTH));

        prefs.putBoolean("toolbarVisible", tlb.isVisible());
        prefs.putBoolean("searchPanelVisible", sidePaneManager.isComponentVisible("search"));
        // Store divider location for side pane:
        int width = contentPane.getDividerLocation();
        if (width > 0) {
            prefs.putInt("sidePaneWidth", width);
        }
        if (prefs.getBoolean("openLastEdited")) {
            // Here we store the names of all current files. If
            // there is no current file, we remove any
            // previously stored file name.
            if (filenames.isEmpty()) {
                prefs.remove("lastEdited");
            } else {
                String[] names = new String[filenames.size()];
                for (int i = 0; i < filenames.size(); i++) {
                    names[i] = filenames.get(i);
                }
                prefs.putStringArray("lastEdited", names);
            }

        }

        fileHistory.storeHistory();
        prefs.customExports.store();
        prefs.customImports.store();
        BibtexEntryType.saveCustomEntryTypes(prefs);

        // Clear autosave files:
        if (Globals.autoSaveManager != null) {
            Globals.autoSaveManager.clearAutoSaves();
        }

        // Let the search interface store changes to prefs.
        // But which one? Let's use the one that is visible.
        if (basePanel() != null) {
            (searchManager).updatePrefs();
        }

        prefs.flush();
    }

    /**
     * Clears fetchers and fetcherActions to prevent memory leaks
     */
    private void clearFetchersAndActions() {
        // Clear fetchers list
        if (fetchers != null) {
            // If fetchers need special cleanup, do it here
            for (EntryFetcher fetcher : fetchers) {
                // Call cleanup method if available
                if (fetcher instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) fetcher).close();
                    } catch (Exception e) {
                        System.err.println("Error closing fetcher: " + e.getMessage());
                    }
                }
            }
            fetchers.clear();
        }

        // Clear fetcherActions list
        if (fetcherActions != null) {
            fetcherActions.clear();
        }

        // Also clear the plugin-related fetchers
        JabRefPlugin jabrefPlugin = JabRefPlugin.getInstance(PluginCore.getManager());
        if (jabrefPlugin != null) {
            // If plugin fetchers need special handling, add it here
        }
    }

    /**
     * General info dialog. The MacAdapter calls this method when "Quit" is
     * selected from the application menu, Cmd-Q is pressed, or "Quit" is
     * selected from the Dock. The function returns a boolean indicating if
     * quitting is ok or not.
     *
     * Non-OSX JabRef calls this when choosing "Quit" from the menu
     *
     * SIDE EFFECT: tears down JabRef
     *
     * @return true if the user chose to quit; false otherwise
     */
    public boolean quit() {
        // Ask here if the user really wants to close, if the base
        // has not been saved since last save.
        boolean close = true;
        List<String> filenames = new ArrayList<>();
        if (tabbedPane.getTabCount() > 0) {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (baseAt(i).isBaseChanged()) {
                    tabbedPane.setSelectedIndex(i);
                    int answer = JOptionPane.showConfirmDialog(JabRefFrame.this, Globals.lang("Database has changed. Do you "
                            + "want to save before closing?"),
                            Globals.lang("Save before closing"),
                            JOptionPane.YES_NO_CANCEL_OPTION);

                    if ((answer == JOptionPane.CANCEL_OPTION)
                            || (answer == JOptionPane.CLOSED_OPTION)) {
                        close = false; // The user has cancelled.
                        return false;
                    }
                    if (answer == JOptionPane.YES_OPTION) {
                        // The user wants to save.
                        try {
                            //basePanel().runCommand("save");
                            SaveDatabaseAction saveAction = new SaveDatabaseAction(basePanel());
                            saveAction.runCommand();
                            if (saveAction.isCancelled() || !saveAction.isSuccess()) {
                                // The action was either cancelled or unsuccessful.
                                // Break!
                                output(Globals.lang("Unable to save database"));
                                close = false;
                            }
                        } catch (Throwable ex) {
                            // Something prevented the file
                            // from being saved. Break!!!
                            close = false;
                            break;
                        }
                    }
                }

                if (baseAt(i).getFile() != null) {
                    filenames.add(baseAt(i).getFile().getAbsolutePath());
                }
            }
        }

        if (close) {

            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (baseAt(i).isSaving()) {
                    // There is a database still being saved, so we need to wait.
                    WaitForSaveOperation w = new WaitForSaveOperation(this);
                    w.show(); // This method won't return until cancelled or the save operation is done.
                    if (w.cancelled()) {
                        return false; // The user clicked cancel.
                    }
                }
            }

            tearDownJabRef(filenames);
            return true;
        }

        return false;
    }

    private void initLayout() {
        // Set FlatLaf compatible backgrounds
//        getContentPane().setBackground(UIManager.getColor("Panel.background"));
//
//        tabbedPane.setBackground(UIManager.getColor("TabbedPane.background"));
//        tabbedPane.setForeground(UIManager.getColor("TabbedPane.foreground"));
//
//        // Style the toolbar
//        tlb.setBackground(UIManager.getColor("ToolBar.background"));
//        tlb.setBorder(UIManager.getBorder("ToolBar.border"));
//

        // Style the status bar
//        statusLine.setBackground(UIManager.getColor("Label.background"));
//        statusLine.setForeground(UIManager.getColor("Label.foreground"));
        statusLabel.setBackground(UIManager.getColor("Label.background"));
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));

//
//        // Style the menu bar
//        mb.setBackground(UIManager.getColor("MenuBar.background"));
//        mb.setBorder(UIManager.getBorder("MenuBar.border"));
        tabbedPane.putClientProperty(Options.NO_CONTENT_BORDER_KEY, Boolean.TRUE);

        setProgressBarVisible(false);

        pushExternalButton = new PushToApplicationButton(this,
                PushToApplicationButton.applications);
        fillMenu();
        createToolBar();
        getContentPane().setLayout(gbl);
        contentPane.setDividerSize(2);
        contentPane.setBorder(null);
        //getContentPane().setBackground(GUIGlobals.lightGray);
        con.fill = GridBagConstraints.HORIZONTAL;
        con.anchor = GridBagConstraints.WEST;
        con.weightx = 1;
        con.weighty = 0;
        con.gridwidth = GridBagConstraints.REMAINDER;

        //gbl.setConstraints(mb, con);
        //getContentPane().add(mb);
        setJMenuBar(mb);
        con.anchor = GridBagConstraints.NORTH;
        //con.gridwidth = 1;//GridBagConstraints.REMAINDER;;
        gbl.setConstraints(tlb, con);
        getContentPane().add(tlb);

        Component lim = Box.createGlue();
        gbl.setConstraints(lim, con);

        con.gridwidth = GridBagConstraints.REMAINDER;
        con.weightx = 1;
        con.weighty = 0;
        con.fill = GridBagConstraints.BOTH;
        con.anchor = GridBagConstraints.WEST;
        con.insets = new Insets(0, 0, 0, 0);
        lim = Box.createGlue();
        gbl.setConstraints(lim, con);
        getContentPane().add(lim);
        //tabbedPane.setVisible(false);
        //tabbedPane.setForeground(GUIGlobals.lightGray);
        con.weighty = 1;
        gbl.setConstraints(contentPane, con);
        getContentPane().add(contentPane);
        contentPane.setRightComponent(tabbedPane);
        contentPane.setLeftComponent(sidePaneManager.getPanel());
        sidePaneManager.updateView();

        JPanel status = new JPanel();
        status.setLayout(gbl);
        con.weighty = 0;
        con.weightx = 0;
        con.gridwidth = 1;
        con.insets = new Insets(0, 2, 0, 0);
        gbl.setConstraints(statusLabel, con);
        status.add(statusLabel);
        con.weightx = 1;
        con.insets = new Insets(0, 4, 0, 0);
        con.gridwidth = 1;
        gbl.setConstraints(statusLine, con);
        status.add(statusLine);
        con.weightx = 0;
        con.gridwidth = GridBagConstraints.REMAINDER;
        con.insets = new Insets(2, 4, 2, 2);
        gbl.setConstraints(progressBar, con);
        status.add(progressBar);
        con.weightx = 1;
        con.gridwidth = GridBagConstraints.REMAINDER;
//        statusLabel.setForeground(GUIGlobals.entryEditorLabelColor.darker());
        con.insets = new Insets(0, 0, 0, 0);
        gbl.setConstraints(status, con);
        getContentPane().add(status);

        // Drag and drop for tabbedPane:
        TransferHandler xfer = new EntryTableTransferHandler(null, this, null);
        tabbedPane.setTransferHandler(xfer);
        tlb.setTransferHandler(xfer);
        mb.setTransferHandler(xfer);
        sidePaneManager.getPanel().setTransferHandler(xfer);
    }

    private void initLabelMaker() {
        // initialize the labelMaker
        labelMaker = new LabelMaker();
        labelMaker.addRule(new ArticleLabelRule(),
                BibtexEntryType.ARTICLE);
        labelMaker.addRule(new BookLabelRule(),
                BibtexEntryType.BOOK);
        labelMaker.addRule(new IncollectionLabelRule(),
                BibtexEntryType.INCOLLECTION);
        labelMaker.addRule(new InproceedingsLabelRule(),
                BibtexEntryType.INPROCEEDINGS);
    }

    /**
     * Returns the indexed BasePanel.
     *
     * @param i Index of base
     * @return BasePanel
     */
    public BasePanel baseAt(int i) {
        return (BasePanel) tabbedPane.getComponentAt(i);
    }

    public void showBaseAt(int i) {
        tabbedPane.setSelectedIndex(i);
    }

    public void showBasePanel(BasePanel bp) {
        tabbedPane.setSelectedComponent(bp);
    }

    /**
     * Returns the currently viewed BasePanel.
     *
     * @return BasePanel
     */
    public BasePanel basePanel() {
        return (BasePanel) tabbedPane.getSelectedComponent();
    }

    /**
     * @return the BasePanel count.
     */
    public int baseCount() {
        return tabbedPane.getComponentCount();
    }

    /**
     * handle the color of active and inactive JTabbedPane tabs
     */
    private void markActiveBasePanel() {
        int now = tabbedPane.getSelectedIndex();
        int len = tabbedPane.getTabCount();
//        if ((lastTabbedPanelSelectionIndex > -1) && (lastTabbedPanelSelectionIndex < len)) {
//            tabbedPane.setBackgroundAt(lastTabbedPanelSelectionIndex, UIManager.getColor("TabbedPane.background"));
//            tabbedPane.setForegroundAt(lastTabbedPanelSelectionIndex, UIManager.getColor("TabbedPane.foreground"));
//        }
//        if ((now > -1) && (now < len)) {
//            tabbedPane.setBackgroundAt(now, UIManager.getColor("TabbedPane.selectedBackground"));
//            tabbedPane.setForegroundAt(now, UIManager.getColor("TabbedPane.selectedForeground"));
//        }
        lastTabbedPanelSelectionIndex = now;
    }

    private int getTabIndex(JComponent comp) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) == comp) {
                return i;
            }
        }
        return -1;
    }

    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    public String getTabTitle(JComponent comp) {
        return tabbedPane.getTitleAt(getTabIndex(comp));
    }

    public String getTabTooltip(JComponent comp) {
        return tabbedPane.getToolTipTextAt(getTabIndex(comp));
    }

    public void setTabTitle(JComponent comp, String title, String toolTip) {
        int index = getTabIndex(comp);
        tabbedPane.setTitleAt(index, title);
        tabbedPane.setToolTipTextAt(index, toolTip);
    }

    class GeneralAction
            extends MnemonicAwareAction {

        private String command;

        public GeneralAction(String command, String text, String description, URL icon) {
            // Always call super() first with null icon
            super((ImageIcon) null);
            this.command = command;

            // Set the icon afterwards
            Icon actionIcon = GUIGlobals.getMenuIcon(command);
            if (actionIcon != null) {
                putValue(Action.SMALL_ICON, actionIcon);
            } else if (icon != null) {
                putValue(Action.SMALL_ICON, new ImageIcon(icon));
            }

            putValue(NAME, text);
            putValue(SHORT_DESCRIPTION, Globals.lang(description));
        }

        public GeneralAction(String command, String text, String description, String imageName, KeyStroke key) {
            // Always call super() first with null icon
            super((ImageIcon) null);
            this.command = command;

            // Set the icon afterwards
            Icon actionIcon = GUIGlobals.getMenuIcon(command);
            if (actionIcon != null) {
                putValue(Action.SMALL_ICON, actionIcon);
            } else {
                ImageIcon legacyIcon = GUIGlobals.getImageIcon(imageName);
                if (legacyIcon != null) {
                    putValue(Action.SMALL_ICON, legacyIcon);
                }
            }
            putValue(NAME, text);
            putValue(ACCELERATOR_KEY, key);
            putValue(SHORT_DESCRIPTION, Globals.lang(description));
        }

        public GeneralAction(String command, String text) {
            putValue(NAME, text);
            this.command = command;
        }

        public GeneralAction(String command, String text, KeyStroke key) {
            this.command = command;
            putValue(NAME, text);
            putValue(ACCELERATOR_KEY, key);
        }

        public GeneralAction(String command, String text, String description) {
            this.command = command;
            ImageIcon icon = GUIGlobals.getImage(command);
            if (icon != null) {
                putValue(SMALL_ICON, icon);
            }
            putValue(NAME, text);
            putValue(SHORT_DESCRIPTION, Globals.lang(description));
        }

        public GeneralAction(String command, String text, String description, KeyStroke key) {
            this.command = command;
            ImageIcon icon = GUIGlobals.getImage(command);
            if (icon != null) {
                putValue(SMALL_ICON, icon);
            }
            putValue(NAME, text);
            putValue(SHORT_DESCRIPTION, Globals.lang(description));
            putValue(ACCELERATOR_KEY, key);
        }

        public GeneralAction(String command, String text, String description, KeyStroke key, String imageUrl) {
            this.command = command;
            ImageIcon icon = GUIGlobals.getImage(imageUrl);
            if (icon != null) {
                putValue(SMALL_ICON, icon);
            }
            putValue(NAME, text);
            putValue(SHORT_DESCRIPTION, Globals.lang(description));
            putValue(ACCELERATOR_KEY, key);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (tabbedPane.getTabCount() > 0) {
                try {
                    ((BasePanel) (tabbedPane.getSelectedComponent()))
                            .runCommand(command);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            } else {
                // QUICK HACK to solve bug #1277
                if (e.getActionCommand().equals("Hide/show toolbar")) {
                    // code copied from BasePanel.java, action "toggleToolbar"
                    tlb.setVisible(!tlb.isVisible());
                } else {
                    Util.pr("Action '" + command + "' must be disabled when no database is open.");
                }
            }
        }
    }

    /**
     * This got removed when we introduced SearchManager2. class
     * IncrementalSearchAction extends AbstractAction { public
     * IncrementalSearchAction() { super("Incremental search", new
     * ImageIcon(GUIGlobals.searchIconFile)); putValue(SHORT_DESCRIPTION,
     * Globals.lang("Start incremental search")); putValue(ACCELERATOR_KEY,
     * prefs.getKey("Incremental search")); } public void
     * actionPerformed(ActionEvent e) { if (tabbedPane.getTabCount() > 0)
     * searchManager.startIncrementalSearch(); } }
     *
     * class SearchAction extends AbstractAction { public SearchAction() {
     * super("Search", new ImageIcon(GUIGlobals.searchIconFile));
     * putValue(SHORT_DESCRIPTION, Globals.lang("Start search"));
     * putValue(ACCELERATOR_KEY, prefs.getKey("Search")); } public void
     * actionPerformed(ActionEvent e) { if (tabbedPane.getTabCount() > 0)
     * searchManager.startSearch(); } }
     */
    class NewEntryAction
            extends MnemonicAwareAction {

        String type = null; // The type of item to create.
        KeyStroke keyStroke = null; // Used for the specific instances.

        public NewEntryAction(KeyStroke key) {
            // This action leads to a dialog asking for entry type.
            super(GUIGlobals.getImage("add"));
            putValue(NAME, "New entry");
            putValue(ACCELERATOR_KEY, key);
            putValue(SHORT_DESCRIPTION, Globals.lang("New BibTeX entry"));
        }

        public NewEntryAction(String type_) {
            // This action leads to the creation of a specific entry.
            putValue(NAME, Util.nCase(type_));
            type = type_;
        }

        public NewEntryAction(String type_, KeyStroke key) {
            // This action leads to the creation of a specific entry.
            putValue(NAME, Util.nCase(type_));
            putValue(ACCELERATOR_KEY, key);
            type = type_;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            String thisType = type;
            if (thisType == null) {
                EntryTypeDialog etd = new EntryTypeDialog(JabRefFrame.this);
                Util.placeDialog(etd, JabRefFrame.this);
                etd.setVisible(true);
                BibtexEntryType tp = etd.getChoice();
                if (tp == null) {
                    return;
                }
                thisType = tp.getName();
            }

            if (tabbedPane.getTabCount() > 0) {
                ((BasePanel) (tabbedPane.getSelectedComponent()))
                        .newEntry(BibtexEntryType.getType(thisType));
            } else {
                Util.pr("Action 'New entry' must be disabled when no "
                        + "database is open.");
            }
        }
    }

    public void setUpImportMenus() {
        setUpImportMenu(importMenu, false);
        setUpImportMenu(importNewMenu, true);
    }

    private void fillMenu() {
        //mb.putClientProperty(Options.HEADER_STYLE_KEY, HeaderStyle.BOTH);
        mb.setBorder(null);
        JMenu file = subMenu("File"),
                sessions = subMenu("Sessions"),
                edit = subMenu("Edit"),
                search = subMenu("Search"),
                bibtex = subMenu("BibTeX"),
                view = subMenu("View"),
                tools = subMenu("Tools"),
                //web = subMenu("Web search"),
                options = subMenu("Options"),
                newSpec = subMenu("New entry..."),
                helpMenu = subMenu("Help");

        setUpImportMenus();

        newDatabaseMenu.add(newDatabaseAction);
        newDatabaseMenu.add(newSubDatabaseAction);

        file.add(createMenuAction(newDatabaseAction, "new"));
        file.add(createMenuAction(open, "open"));
        file.add(createMenuAction(mergeDatabaseAction, "append"));
        file.add(createMenuAction(save, "save"));
        file.add(createMenuAction(saveAs, "saveAs"));
        file.add(createMenuAction(saveAll, "saveAll"));
        file.add(createMenuAction(saveSelectedAs, "saveAs"));
        file.add(createMenuAction(saveSelectedAsPlain, "saveAs"));

        file.addSeparator();
        file.add(createMenuAction(importNew, "importNew"));
        file.add(createMenuAction(importCurrent, "importCurrent"));
        file.add(createMenuAction(exportAll, "exportFile"));
        file.add(createMenuAction(exportSelected, "exportFile"));
        file.add(createMenuAction(dbConnect, "dbConnect"));
        file.add(createMenuAction(dbImport, "dbImport"));
        file.add(createMenuAction(dbExport, "dbExport"));

        file.addSeparator();
        file.add(createMenuAction(databaseProperties, "databaseProperties"));
        file.addSeparator();

        sessions.add(loadSessionAction);
        sessions.add(saveSessionAction);
        file.add(sessions);
        file.add(fileHistory);

        file.addSeparator();
        file.add(createMenuAction(close, "closeFile"));
        file.add(new MinimizeToSysTrayAction());
        file.add(createMenuAction(quit, "quit"));
        mb.add(file);

        //edit.add(test);
        edit.add(createMenuAction(undo, "undo"));
        edit.add(createMenuAction(redo, "redo"));
        edit.addSeparator();

        edit.add(createMenuAction(cut, "cut"));
        edit.add(createMenuAction(copy, "copy"));
        edit.add(createMenuAction(paste, "paste"));
        //edit.add(remove);
        edit.add(createMenuAction(delete, "delete"));

        edit.addSeparator();
        edit.add(createMenuAction(copyKey, "copy"));
        edit.add(createMenuAction(copyCiteKey, "copy"));
        edit.add(createMenuAction(copyKeyAndTitle, "copy"));
        //edit.add(exportToClipboard);

        edit.addSeparator();
        edit.add(createMenuAction(mark, "mark"));
        JMenu markSpecific = subMenu("Mark specific color");
        for (int i = 0; i < Util.MAX_MARKING_LEVEL; i++) {
            markSpecific.add(new MarkEntriesAction(this, i).getMenuItem());
        }
        edit.add(markSpecific);
        edit.add(createMenuAction(unmark, "unmark"));
        // edit.add(createMenuAction(unmarkAll, "unmark"));
        // edit.addSeparator();
        if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SPECIALFIELDSENABLED)) {
            JMenu m;
            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_RANKING)) {
                m = new JMenu();
                RightClickMenu.populateSpecialFieldMenu(m, Rank.getInstance(), this);
                edit.add(m);
            }
            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_RELEVANCE)) {
                edit.add(toggleRelevance);
            }
            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_QUALITY)) {
                edit.add(toggleQualityAssured);
            }
            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_PRIORITY)) {
                m = new JMenu();
                RightClickMenu.populateSpecialFieldMenu(m, Priority.getInstance(), this);
                edit.add(m);
            }
            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_PRINTED)) {
                edit.add(togglePrinted);
            }
            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_READ)) {
                m = new JMenu();
                RightClickMenu.populateSpecialFieldMenu(m, ReadStatus.getInstance(), this);
                edit.add(m);
            }
        }
        edit.addSeparator();
        edit.add(manageKeywords);
        edit.add(createMenuAction(selectAll, "selectAll"));
        mb.add(edit);

        search.add(createMenuAction(normalSearch, "search"));
        search.add(createMenuAction(incrementalSearch, "search"));
        search.add(createMenuAction(replaceAll, "replaceAll"));
        search.add(createMenuAction(massSetField, "setField"));

        search.addSeparator();
        search.add(createMenuAction(dupliCheck, "duplicate"));
        search.add(createMenuAction(resolveDuplicateKeys, "duplicate"));

        //search.add(strictDupliCheck);
        search.add(autoSetFile);
        search.addSeparator();
        GeneralFetcher generalFetcher = new GeneralFetcher(sidePaneManager, this, fetchers);
        search.add(generalFetcher.getAction());
        if (prefs.getBoolean("webSearchVisible")) {
            sidePaneManager.register(generalFetcher.getTitle(), generalFetcher);
            sidePaneManager.show(generalFetcher.getTitle());
        }
        mb.add(search);

        view.add(createMenuAction(back, "back"));
        view.add(createMenuAction(forward, "forward"));
        view.add(focusTable);
        view.add(createMenuAction(nextTab, "nextTab"));
        view.add(createMenuAction(prevTab, "prevTab"));
        view.add(createMenuAction(sortTabs, "sort"));
        view.addSeparator();
        view.add(createMenuAction(increaseFontSize, "fontSizeUp"));
        view.add(createMenuAction(decreseFontSize, "fontSizeDown"));
        view.addSeparator();
        view.add(createMenuAction(toggleToolbar, "toolbar"));
        view.add(createMenuAction(toggleGroups, "toggleGroups"));
        view.add(createMenuAction(togglePreview, "togglePreview"));
        view.add(createMenuAction(switchPreview, "togglePreview"));
        view.addSeparator();
        view.add(createMenuAction(toggleHighlightAny, "groupsHighlightAny"));
        view.add(createMenuAction(toggleHighlightAll, "groupsHighlightAll"));
        mb.add(view);

        bibtex.add(newEntryAction);
        for (NewEntryAction aNewSpecificEntryAction : newSpecificEntryAction) {
            newSpec.add(aNewSpecificEntryAction);
        }
        bibtex.add(newSpec);
        bibtex.add(plainTextImport);
        bibtex.addSeparator();
        bibtex.add(editEntry);
        bibtex.add(editPreamble);
        bibtex.add(editStrings);
        mb.add(bibtex);

        tools.add(createMenuAction(makeKeyAction, "makeKey"));
        tools.add(createMenuAction(Cleanup, "Cleanup"));
        tools.add(createMenuAction(mergeEntries, "mergeentries"));
        //tools.add(downloadFullText);
        tools.add(createMenuAction(newSubDatabaseAction, "new"));
        tools.add(createMenuAction(writeXmpAction, "xmp"));
//        OpenOfficePanel otp = OpenOfficePanel.getInstance();
//        otp.init(this, sidePaneManager);
//        tools.add(otp.getMenuItem());
//        tools.add(pushExternalButton.getMenuAction());//***********************
        tools.addSeparator();                         //***********************
        tools.add(manageSelectors);                   //***********************
        tools.addSeparator();                         //***********************
        tools.add(createMenuAction(openFolder, "openFolder"));
        tools.add(createMenuAction(openFile, "openFile"));
        tools.add(createMenuAction(openPdf, "pdfSmall"));
        tools.add(createMenuAction(openUrl, "openUrl"));
        //tools.add(openSpires);
        tools.add(createMenuAction(findUnlinkedFiles, "search"));
        tools.add(createMenuAction(autoLinkFile, "linkFile"));
        tools.addSeparator();
        tools.add(createMenuAction(abbreviateIso, "abbreviate")); //"abbreviate"
        tools.add(createMenuAction(abbreviateMedline, "abbreviate")); //"abbreviate"
        tools.add(createMenuAction(unabbreviate, "unabbreviate")); //"unabbreviate"
        tools.addSeparator();
        checkAndFix.add(autoSetPdf);
        checkAndFix.add(autoSetPs);
        checkAndFix.add(integrityCheckAction);
        tools.add(checkAndFix);

        mb.add(tools);

        pluginMenu.add(installPlugin);
        //pluginMenu.setEnabled(false);
        mb.add(pluginMenu);

        options.add(createMenuAction(showPrefs, "preferences"));
        AbstractAction customizeAction = new CustomizeEntryTypeAction();
        AbstractAction genFieldsCustomization = new GenFieldsCustomizationAction();
        options.add(customizeAction);
        options.add(genFieldsCustomization);
        options.add(createMenuAction(customExpAction, "exportFile"));
        options.add(createMenuAction(customImpAction, "importNew"));
        options.add(createMenuAction(customFileTypesAction, "fileType"));
        options.add(createMenuAction(manageJournals, "abbreviate"));
        options.add(createMenuAction(selectKeys, "keys"));
        mb.add(options);

        helpMenu.add(createMenuAction(help, "help"));
        helpMenu.add(createMenuAction(contents, "helpContents"));
        helpMenu.addSeparator();
        helpMenu.add(createMenuAction(errorConsole, "logError"));
        helpMenu.add(createMenuAction(forkMeOnGitHubAction, "github")); //
        helpMenu.addSeparator();
        helpMenu.add(createMenuAction(about, "about"));
        mb.add(helpMenu);
    }

    /**
     * Create menu action with consistent SVG icon handling (16x16 for menus)
     */
    private Action createMenuAction(Action originalAction, String iconName) {
        Action menuAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                originalAction.actionPerformed(e);
            }
        };

        // Copy properties from original action
        menuAction.putValue(Action.NAME, originalAction.getValue(Action.NAME));
        menuAction.putValue(Action.SHORT_DESCRIPTION, originalAction.getValue(Action.SHORT_DESCRIPTION));
        menuAction.putValue(Action.ACCELERATOR_KEY, originalAction.getValue(Action.ACCELERATOR_KEY));

        // Set menu-specific icon (16x16)
        Icon menuIcon = GUIGlobals.getIcon(iconName, GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (menuIcon != null) {
            menuAction.putValue(Action.SMALL_ICON, menuIcon);
        } else {
            // Fallback: use original action's icon (get it from SMALL_ICON, not SHORT_DESCRIPTION)
            Icon originalIcon = (Icon) originalAction.getValue(Action.SMALL_ICON);
            if (originalIcon != null) {
                menuAction.putValue(Action.SMALL_ICON, originalIcon);
            } else {
                System.err.println("Warning: No menu icon found for: " + iconName);
                // Don't set any icon if none is available
            }
        }

        return menuAction;
    }

    public static JMenu subMenu(String name) {
        name = Globals.menuTitle(name);
        int i = name.indexOf('&');
        JMenu res;
        if (i >= 0) {
            res = new JMenu(name.substring(0, i) + name.substring(i + 1));
            char mnemonic = Character.toUpperCase(name.charAt(i + 1));
            res.setMnemonic((int) mnemonic);
        } else {
            res = new JMenu(name);
        }

        return res;
    }

    public void addParserResult(ParserResult pr, boolean raisePanel) {
        if (pr.toOpenTab()) {
            // Add the entries to the open tab.
            BasePanel panel = basePanel();
            if (panel == null) {
                // There is no open tab to add to, so we create a new tab:
                addTab(pr.getDatabase(), pr.getFile(), pr.getMetaData(), pr.getEncoding(), raisePanel);
            } else {
                List<BibtexEntry> entries = new ArrayList<>(pr.getDatabase().getEntries());
                addImportedEntries(panel, entries, "", false);
            }
        } else {
            addTab(pr.getDatabase(), pr.getFile(), pr.getMetaData(), pr.getEncoding(), raisePanel);
        }
    }

    public void addPluginMenuItem(JMenuItem item) {
        if (!addedToPluginMenu) {
            pluginMenu.addSeparator();
            addedToPluginMenu = true;
        }
        pluginMenu.add(item);
    }

    class ToolbarSizeSelector extends JComboBox<String> implements ActionListener {

        public ToolbarSizeSelector() {
            super(new String[]{"Small", "Medium", "Large"});

            // Set current selection based on global size
            switch (GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE) {
                case GUIGlobals.TOOLBAR_ICON_SMALL:
                    setSelectedIndex(0);
                    break;
                case GUIGlobals.TOOLBAR_ICON_MEDIUM:
                    setSelectedIndex(1);
                    break;
                case GUIGlobals.TOOLBAR_ICON_LARGE:
                    setSelectedIndex(2);
                    break;
            }
            setToolTipText("Toolbar icon size");
            // Dynamic sizing based on content and toolbar
            configureDynamicSizing();

            addActionListener(this);
        }

        private void configureDynamicSizing() {
            // Calculate optimal width based on content
            FontMetrics metrics;
            metrics = getFontMetrics(getFont());
            int maxWidth = 0;

            for (int i = 0; i < getItemCount(); i++) {
                int itemWidth = metrics.stringWidth(getItemAt(i));
                maxWidth = Math.max(maxWidth, itemWidth);
            }

            // Add padding for dropdown arrow and borders
            int padding = 20; // Space for dropdown arrow and borders
            int optimalWidth = maxWidth + padding;

            // Set height to match toolbar icon size for consistency
            int optimalHeight = GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE;

            // Set preferred size (will be used by layout manager)
            setPreferredSize(new Dimension(optimalWidth, optimalHeight));

            // Set minimum size to prevent it from becoming too small
            setMinimumSize(new Dimension(80, optimalHeight));

            // Maximum size can be slightly larger than preferred
            setMaximumSize(new Dimension(optimalWidth + 20, optimalHeight));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int newSize;
            switch (getSelectedIndex()) {
                case 0:
                    newSize = GUIGlobals.TOOLBAR_ICON_SMALL;
                    break;
                case 1:
                    newSize = GUIGlobals.TOOLBAR_ICON_MEDIUM;
                    break;
                case 2:
                    newSize = GUIGlobals.TOOLBAR_ICON_LARGE;
                    break;
                default:
                    newSize = GUIGlobals.TOOLBAR_ICON_MEDIUM;
            }

            // Update global size
            GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE = newSize;

            // Save preference
            Globals.prefs.putInt("toolbarIconSize", newSize);

            // Refresh the toolbar
            refreshToolbarIcons();
        }
    }

    private void refreshToolbarIcons() {
        // Store current selection before refresh
        String currentApp = null;
        if (pushExternalButton != null && !pushExternalButton.pushActions.isEmpty()) {
            currentApp = pushExternalButton.pushActions.get(pushExternalButton.selected).getName();
        }

        // Remove all toolbar components
        tlb.removeAll();

        // Recreate the push external button entirely
        pushExternalButton = new PushToApplicationButton(this, PushToApplicationButton.applications);

        // Restore previous selection if it exists
        if (currentApp != null) {
            for (int i = 0; i < pushExternalButton.pushActions.size(); i++) {
                if (pushExternalButton.pushActions.get(i).getName().equals(currentApp)) {
                    pushExternalButton.setSelected(i);
                    break;
                }
            }
        }

        // Recreate the entire toolbar
        createToolBar();

        tlb.revalidate();
        tlb.repaint();
        revalidate();
        repaint();
    }

    private void createToolBar() {
        tlb.putClientProperty(Options.HEADER_STYLE_KEY, HeaderStyle.BOTH);
        tlb.setBorder(null);
        tlb.setRollover(true);
        tlb.setFloatable(false);

        // tlb.setBorderPainted(true);
        // tlb.setBackground(GUIGlobals.lightGray);
        // tlb.setForeground(GUIGlobals.lightGray);
        tlb.addAction(createToolbarAction(newDatabaseAction, "new"));
        tlb.addAction(createToolbarAction(open, "open"));
        tlb.addAction(createToolbarAction(save, "save"));
        tlb.addAction(createToolbarAction(saveAll, "saveAll"));

        tlb.addSeparator();
        tlb.addAction(createToolbarAction(cut, "cut"));
        tlb.addAction(createToolbarAction(copy, "copy"));
        tlb.addAction(createToolbarAction(paste, "paste"));
        tlb.addAction(createToolbarAction(undo, "undo"));
        tlb.addAction(createToolbarAction(redo, "redo"));

        // tlb.addSeparator();
        // tlb.addAction(createToolbarAction(back, "left"));
        // tlb.addAction(createToolbarAction(forward, "right"));
        tlb.addSeparator();
        tlb.addAction(createToolbarAction(newEntryAction, "add"));
        tlb.addAction(createToolbarAction(editEntry, "edit"));
        tlb.addAction(createToolbarAction(editPreamble, "editPreamble"));
        tlb.addAction(createToolbarAction(editStrings, "editStrings"));
        tlb.addAction(createToolbarAction(makeKeyAction, "makeKey"));
        tlb.addAction(createToolbarAction(Cleanup, "cleanupentries"));
        tlb.addAction(createToolbarAction(mergeEntries, "mergeentries"));

        tlb.addSeparator();
        tlb.addAction(createToolbarAction(mark, "markEntries"));
        tlb.addAction(createToolbarAction(unmark, "unmarkEntries"));

//        tlb.addSeparator();
//        if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SPECIALFIELDSENABLED)) {
//            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_RANKING)) {
//                tlb.add(net.sf.jabref.specialfields.SpecialFieldDropDown.generateSpecialFieldButtonWithDropDown(Rank.getInstance(), this));
//            }
//            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_RELEVANCE)) {
//                // tlb.addAction(toggleRelevance);
//                tlb.addAction(createActionWithIcon(toggleRelevance, "toggleRelevance"));
//            }
//            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_QUALITY)) {
//                // tlb.addAction(toggleQualityAssured);
//                tlb.addAction(createActionWithIcon(toggleQualityAssured, "toggleQualityAssured"));
//            }
//            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_PRIORITY)) {
//                tlb.add(net.sf.jabref.specialfields.SpecialFieldDropDown.generateSpecialFieldButtonWithDropDown(Priority.getInstance(), this));
//            }
//            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_PRINTED)) {
//                // tlb.addAction(togglePrinted);
//                tlb.addAction(createActionWithIcon(togglePrinted, "togglePrinted"));
//            }
//            if (Globals.prefs.getBoolean(SpecialFieldsUtils.PREF_SHOWCOLUMN_READ)) {
//                tlb.add(net.sf.jabref.specialfields.SpecialFieldDropDown.generateSpecialFieldButtonWithDropDown(ReadStatus.getInstance(), this));
//            }
//        }
        tlb.addSeparator();
//        searchToggle = new JToggleButton(toggleSearch);
//        searchToggle.setText(null);
//        if (!Globals.ON_MAC) {
//            searchToggle.setMargin(marg);
//        }
//        tlb.add(searchToggle);
//
//        previewToggle = new JToggleButton(togglePreview);
//        previewToggle.setText(null);
//        if (!Globals.ON_MAC) {
//            previewToggle.setMargin(marg);
//        }
//        tlb.add(previewToggle);
//        tlb.addSeparator();
//
//        groupToggle = new JToggleButton(toggleGroups);
//        groupToggle.setText(null);
//        if (!Globals.ON_MAC) {
//            groupToggle.setMargin(marg);
//        }
//        tlb.add(groupToggle);

        searchToggle = createToolbarToggleButton(toggleSearch, "toggleSearch");
        tlb.add(searchToggle);

        previewToggle = createToolbarToggleButton(togglePreview, "togglePreview");
        tlb.add(previewToggle);
        tlb.addSeparator();

        groupToggle = createToolbarToggleButton(toggleGroups, "toggleGroups");
        tlb.add(groupToggle);

        highlightAny = createToolbarToggleButton(toggleHighlightAny, "groupsHighlightAny");
        tlb.add(highlightAny);

        highlightAll = createToolbarToggleButton(toggleHighlightAll, "groupsHighlightAll");
        tlb.add(highlightAll);

        tlb.addSeparator();

        // Removing the separate push-to buttons, replacing them by the
        // multipurpose button:
        //tlb.addAction(emacsPushAction);
        //tlb.addAction(lyxPushAction);
        //tlb.addAction(winEdtPushAction);
        tlb.add(pushExternalButton.getComponent()); //***********************
//        tlb.add(createToolbarPushButton());
        tlb.addSeparator();

        tlb.addAction(createToolbarAction(openFolder, "openFolder"));
        tlb.addAction(createToolbarAction(openFile, "openFile"));

        //tlb.addAction(openPdf);
        //tlb.addAction(openUrl);
        tlb.addSeparator();
        tlb.addAction(createToolbarAction(showPrefs, "preferences"));

        tlb.addSeparator();
        tlb.add(new ToolbarSizeSelector());

        tlb.add(Box.createHorizontalGlue());
        //tlb.add(new JabRefLabel(GUIGlobals.frameTitle+" "+GUIGlobals.version));

        // tlb.addAction(closeDatabaseAction);
        tlb.addAction(createActionWithIcon(closeDatabaseAction, "close"));//closeDatabaseAction
        //Insets margin = new Insets(0, 0, 0, 0);
        //for (int i=0; i<tlb.getComponentCount(); i++)
        //  ((JButton)tlb.getComponentAtIndex(i)).setMargin(margin);
    }

    /**
     * Create same styled PushToApplicationButton for the toolbar
     */
    private Component createToolbarPushButton() {
        Component originalComponent = pushExternalButton.getComponent();

        if (originalComponent instanceof JPanel) {
            JPanel panel = (JPanel) originalComponent;

            panel.setOpaque(false);

            // Find the buttons within the panel
            for (Component comp : panel.getComponents()) {

                if (comp instanceof JButton) {
                    JButton button = (JButton) comp;

                    // Apply consistent toolbar styling with transparency
                    button.setText(null);
                    button.setOpaque(false); // Make button transparent
                    button.setContentAreaFilled(false); // content area fill
                    button.setBorderPainted(true); // border
                    if (!Globals.ON_MAC) {
                        button.setMargin(new Insets(0, 0, 0, 0));
                    }

                    // For the main push button, try to set a consistent icon
                    if (button.getActionListeners().length > 0
                            && button.getActionListeners()[0] == pushExternalButton) {
                        // This is the main push button
                        Icon pushIcon = GUIGlobals.getToolbarIconOnly("externalApp");
                        if (pushIcon != null) {
                            button.setIcon(pushIcon);
                        }
//                        System.out.println(button);
                    } else {
                        // This is the dropdown arrow button
                        Icon arrowIcon = GUIGlobals.getToolbarIconOnly("OverflowDropdown");

                        if (arrowIcon != null) {
                            button.setIcon(arrowIcon);
                        }
                    }
                }
            }
        }

        return originalComponent;
    }

    /**
     * Helper method to create actions with Icons (supports both SVG and legacy)
     */
    private Action createActionWithIcon(Action action, String iconName) {
        // Store the icon name in the action for later updates
        action.putValue("iconName", iconName);

        Icon icon = GUIGlobals.getImage(iconName);
        if (icon != null) {
            action.putValue(Action.SMALL_ICON, icon);
        }
        return action;
    }
//    private Action createActionWithIcon(Action originalAction, String iconName) {
//        Action toolbarAction = new AbstractAction() {
//            @Override
//            public void actionPerformed(ActionEvent e) {
//                originalAction.actionPerformed(e);
//            }
//        };
//
//        // Copy properties
//        toolbarAction.putValue(Action.NAME, originalAction.getValue(Action.NAME));
//        toolbarAction.putValue(Action.SHORT_DESCRIPTION, originalAction.getValue(Action.SHORT_DESCRIPTION));
//
//        // Try to get toolbar icon first
//        Icon toolbarIcon = GUIGlobals.getIcon(iconName, GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
//
//        toolbarAction.putValue(Action.SMALL_ICON, toolbarIcon);
//        return toolbarAction;
//    }

    /**
     * Create toolbar action with toolbar-specific icon
     */
    private Action createToolbarAction(Action originalAction, String iconName) {
        Action toolbarAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                originalAction.actionPerformed(e);
            }
        };

        // Debug: Check if icon exists
        Icon testIcon = GUIGlobals.getToolbarIconOnly(iconName);
        if (testIcon == null) {
//            System.out.println("Missing toolbar icon: " + iconName);
            // List all available icons for reference
            Map<String, String> allIcons = GUIGlobals.getAllIcons();
//            System.out.println("Available icons: " + allIcons.keySet());
        }

        // Copy properties
        toolbarAction.putValue(Action.NAME, originalAction.getValue(Action.NAME));
        toolbarAction.putValue(Action.SHORT_DESCRIPTION, originalAction.getValue(Action.SHORT_DESCRIPTION));

        // Try to get toolbar icon first
        Icon toolbarIcon = GUIGlobals.getToolbarIconOnly(iconName);

        if (toolbarIcon != null) {
            toolbarAction.putValue(Action.SMALL_ICON, toolbarIcon);
        } else {
            // Fallback 1: Try menu icon
            Icon menuIcon = GUIGlobals.getMenuIconOnly(iconName);
            if (menuIcon != null) {
                toolbarAction.putValue(Action.SMALL_ICON, menuIcon);
            } else {
                // Fallback 2: Use original action's icon
                Icon originalIcon = (Icon) originalAction.getValue(Action.SMALL_ICON);
                if (originalIcon != null) {
                    toolbarAction.putValue(Action.SMALL_ICON, originalIcon);
                } else {
                    System.err.println("Warning: No icon found for: " + iconName);
                }
            }
        }
        return toolbarAction;
    }

    /**
     * Create toolbar toggle button with consistent styling and icon handling
     */
    private JToggleButton createToolbarToggleButton(Action action, String iconName) {
        JToggleButton button = new JToggleButton(action);
        button.setText(null);

        // Set toolbar-specific icon (24x24)
        Icon toolbarIcon = GUIGlobals.getToolbarIconOnly(iconName);
        if (toolbarIcon != null) {
            button.setIcon(toolbarIcon);
        } else {
            // Fallback: try regular icon
            Icon fallbackIcon = GUIGlobals.getIcon(iconName);
            if (fallbackIcon != null) {
                button.setIcon(fallbackIcon);
            } else {
                // Final fallback: use the action's icon
                Icon actionIcon = (Icon) action.getValue(Action.SMALL_ICON);
                if (actionIcon != null) {
                    button.setIcon(actionIcon);
                }
            }
        }

        // Apply consistent styling
        if (!Globals.ON_MAC) {
            button.setMargin(marg);
        }

        return button;
    }

    public void output(final String s) {
        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                statusLine.setText(s);
                statusLine.repaint();
            }
        });
    }

    public void stopShowingSearchResults() {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            baseAt(i).stopShowingSearchResults();
        }
    }

    protected List<Object> openDatabaseOnlyActions = new LinkedList<>();
    protected List<Object> severalDatabasesOnlyActions = new LinkedList<>();

    protected void initActions() {
        openDatabaseOnlyActions = new LinkedList<>();
        openDatabaseOnlyActions.addAll(Arrays.asList(manageSelectors,
                mergeDatabaseAction, newSubDatabaseAction, close, save, saveAs, saveSelectedAs, saveSelectedAsPlain, undo,
                redo, cut, delete, copy, paste, mark, unmark, unmarkAll, editEntry,
                selectAll, copyKey, copyCiteKey, copyKeyAndTitle, editPreamble, editStrings, toggleGroups, toggleSearch,
                makeKeyAction, normalSearch,
                incrementalSearch, replaceAll, importMenu, exportMenu,
                openPdf, openUrl, openFolder, openFile, togglePreview, dupliCheck, /*strictDupliCheck,*/ highlightAll,
                highlightAny, newEntryAction, plainTextImport, massSetField, manageKeywords,
                closeDatabaseAction, switchPreview, integrityCheckAction, autoSetPdf, autoSetPs,
                toggleHighlightAny, toggleHighlightAll, databaseProperties, abbreviateIso,
                abbreviateMedline, unabbreviate, exportAll, exportSelected,
                importCurrent, saveAll, dbConnect, dbExport, focusTable));

        openDatabaseOnlyActions.addAll(fetcherActions);

        openDatabaseOnlyActions.addAll(Arrays.asList(newSpecificEntryAction));

        severalDatabasesOnlyActions = new LinkedList<>();
        severalDatabasesOnlyActions.addAll(Arrays
                .asList(nextTab, prevTab, sortTabs));

        tabbedPane.addChangeListener(new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent event) {
                updateEnabledState();
            }
        });

    }

    /**
     * Takes a list of Object and calls the method setEnabled on them, depending
     * on whether it is an Action or a Component.
     *
     * @param list List that should contain Actions and Components.
     * @param enabled
     */
    public static void setEnabled(List<Object> list, boolean enabled) {
        for (Object o : list) {
            if (o instanceof Action) {
                ((Action) o).setEnabled(enabled);
            }
            if (o instanceof Component) {
                ((Component) o).setEnabled(enabled);
            }
        }
    }

    protected int previousTabCount = -1;

    /**
     * Enable or Disable all actions based on the number of open tabs.
     *
     * The action that are affected are set in initActions.
     */
    protected void updateEnabledState() {
        int tabCount = tabbedPane.getTabCount();
        if (tabCount != previousTabCount) {
            previousTabCount = tabCount;
            setEnabled(openDatabaseOnlyActions, tabCount > 0);
            setEnabled(severalDatabasesOnlyActions, tabCount > 1);
        }
        if (tabCount == 0) {
            back.setEnabled(false);
            forward.setEnabled(false);
        }
    }

    /**
     * This method causes all open BasePanels to set up their tables anew. When
     * called from PrefsDialog3, this updates to the new settings.
     */
    public void setupAllTables() {
        // This action can be invoked without an open database, so
        // we have to check if we have one before trying to invoke
        // methods to execute changes in the preferences.

        // We want to notify all tabs about the changes to
        // avoid problems when changing the column set.
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            BasePanel bf = baseAt(i);

            // Update tables:
            if (bf.database != null) {
                bf.setupMainPanel();

            }

        }
    }

    public BasePanel addTab(BibtexDatabase db, File file, MetaData metaData, String encoding, boolean raisePanel) {
        // ensure that non-null parameters are really non-null
        if (metaData == null) {
            metaData = new MetaData();
        }
        if (encoding == null) {
            encoding = Globals.prefs.get("defaultEncoding");
        }

        BasePanel bp = new BasePanel(JabRefFrame.this, db, file, metaData, encoding);
        addTab(bp, file, raisePanel);
        return bp;
    }

    public void addTab(BasePanel bp, File file, boolean raisePanel) {
        String title;
        if (file == null) {
            title = Globals.lang(GUIGlobals.untitledTitle);
            if (!bp.database().getEntries().isEmpty()) {
                // if the database is not empty and no file is assigned,
                // the database came from an import and has to be treated somehow
                // -> mark as changed
                // This also happens internally at basepanel to ensure consistency
                title = title + "*";
            }
        } else {
            title = file.getName();
        }
        tabbedPane.add(title, bp);
        tabbedPane.setToolTipTextAt(tabbedPane.getTabCount() - 1,
                file != null ? file.getAbsolutePath() : null);
        if (raisePanel) {
            tabbedPane.setSelectedComponent(bp);
        }
    }

    /**
     * Signal closing of the current tab. Standard warnings will be given if the
     * database has been changed.
     */
    public void closeCurrentTab() {
        closeDatabaseAction.actionPerformed(null);
    }

    /**
     * Close the current tab without giving any warning if the database has been
     * changed.
     */
    public void closeCurrentTabNoWarning() {
        closeDatabaseAction.close();
    }

    class SelectKeysAction
            extends AbstractAction {

        public SelectKeysAction() {
            super(Globals.lang("Customize key bindings"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            KeyBindingsDialog d = new KeyBindingsDialog(new HashMap<String, String>(prefs.getKeyBindings()),
                    prefs.getDefaultKeys());
            d.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            d.pack(); //setSize(300,500);
            Util.placeDialog(d, JabRefFrame.this);
            d.setVisible(true);
            if (d.getAction()) {
                prefs.setNewKeyBindings(d.getNewKeyBindings());
                JOptionPane.showMessageDialog(JabRefFrame.this,
                        Globals.lang("Your new key bindings have been stored.") + "\n"
                        + Globals.lang("You must restart JabRef for the new key "
                                + "bindings to work properly."),
                        Globals.lang("Key bindings changed"),
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    /**
     * The action concerned with closing the window.
     */
    class CloseAction
            extends MnemonicAwareAction {

        public CloseAction() {
            putValue(NAME, "Quit");
            putValue(SHORT_DESCRIPTION, Globals.lang("Quit JabRef"));
            putValue(ACCELERATOR_KEY, prefs.getKey("Quit JabRef"));
            //putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Q,
            //    Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));

        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (quit()) {
                // FIXME: tearDownJabRef() does not cancel all threads. Therefore, some threads remain running and prevent JabRef from beeing unloaded completely
                // QUICKHACK: finally tear down all existing threads
                System.exit(0);
            }
        }
    }

    // The action for closing the current database and leaving the window open.
    CloseDatabaseAction closeDatabaseAction = new CloseDatabaseAction();

    class CloseDatabaseAction extends MnemonicAwareAction {

        public CloseDatabaseAction() {
            super(GUIGlobals.getImage("close"));
            putValue(NAME, "Close database");
            putValue(SHORT_DESCRIPTION, Globals.lang("Close the current database"));
            putValue(ACCELERATOR_KEY, prefs.getKey("Close database"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // Ask here if the user really wants to close, if the base
            // has not been saved since last save.
            boolean close = true;
            if (basePanel() == null) { // when it is initially empty
                return; // nbatada nov 7
            }

            if (basePanel().isBaseChanged()) {
                int answer = JOptionPane.showConfirmDialog(JabRefFrame.this,
                        Globals.lang("Database has changed. Do you want to save before closing?"),
                        Globals.lang("Save before closing"), JOptionPane.YES_NO_CANCEL_OPTION);
                if ((answer == JOptionPane.CANCEL_OPTION) || (answer == JOptionPane.CLOSED_OPTION)) {
                    close = false; // The user has cancelled.
                }
                if (answer == JOptionPane.YES_OPTION) {
                    // The user wants to save.
                    try {
                        SaveDatabaseAction saveAction = new SaveDatabaseAction(basePanel());
                        saveAction.runCommand();
                        if (saveAction.isCancelled() || !saveAction.isSuccess()) // The action either not cancelled or unsuccessful.
                        // Break! 
                        {
                            close = false;
                        }

                    } catch (Throwable ex) {
                        // Something prevented the file
                        // from being saved. Break!!!
                        close = false;
                    }

                }
            }

            if (close) {
                close();
            }
        }

        public void close() {
            BasePanel pan = basePanel();
            pan.cleanUp();
            AutoSaveManager.deleteAutoSaveFile(pan); // Delete autosave
            tabbedPane.remove(pan);
            if (tabbedPane.getTabCount() > 0) {
                markActiveBasePanel();
            }
            setWindowTitle();

            updateEnabledState(); // Man, this is what I call a bug that this is not called.
            output(Globals.lang("Closed database") + ".");
            System.gc(); // Test
        }
    }

    // The action concerned with opening a new database.
    class NewDatabaseAction
            extends MnemonicAwareAction {

        public NewDatabaseAction() {
            super(GUIGlobals.getImage("new"));
            putValue(NAME, "New database");
            putValue(SHORT_DESCRIPTION, Globals.lang("New BibTeX database"));
            //putValue(MNEMONIC_KEY, GUIGlobals.newKeyCode);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // Create a new, empty, database.
            BibtexDatabase database = new BibtexDatabase();
            addTab(database, null, new MetaData(), Globals.prefs.get("defaultEncoding"), true);
            output(Globals.lang("New database created."));
        }
    }

    // The action concerned with generate a new (sub-)database from latex aux file.
    class NewSubDatabaseAction extends MnemonicAwareAction {

        public NewSubDatabaseAction() {
            super(GUIGlobals.getImage("new"));
            putValue(NAME, "New subdatabase based on AUX file");
            putValue(SHORT_DESCRIPTION, Globals.lang("New BibTeX subdatabase"));
            //putValue(MNEMONIC_KEY, GUIGlobals.newKeyCode);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // Create a new, empty, database.

            FromAuxDialog dialog = new FromAuxDialog(JabRefFrame.this, "", true, JabRefFrame.this.tabbedPane);

            Util.placeDialog(dialog, JabRefFrame.this);
            dialog.setVisible(true);

            if (dialog.generatePressed()) {
                BasePanel bp = new BasePanel(JabRefFrame.this,
                        dialog.getGenerateDB(), // database
                        null, // file
                        new MetaData(), Globals.prefs.get("defaultEncoding"));                     // meta data
                tabbedPane.add(Globals.lang(GUIGlobals.untitledTitle), bp);
                tabbedPane.setSelectedComponent(bp);
                output(Globals.lang("New database created."));
            }
        }
    }

    // The action should test the database and report errors/warnings
    class IntegrityCheckAction extends AbstractAction {

        public IntegrityCheckAction() {
            super(Globals.menuTitle("Integrity check"),
                    GUIGlobals.getImage("integrityCheck"));
            //putValue( SHORT_DESCRIPTION, "integrity" ) ;  //Globals.lang( "integrity" ) ) ;
            //putValue(MNEMONIC_KEY, GUIGlobals.newKeyCode);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Object selComp = tabbedPane.getSelectedComponent();
            if (selComp != null) {
                BasePanel bp = (BasePanel) selComp;
                BibtexDatabase refBase = bp.getDatabase();
                if (refBase != null) {
                    IntegrityWizard wizard = new IntegrityWizard(JabRefFrame.this, basePanel());
                    Util.placeDialog(wizard, JabRefFrame.this);
                    wizard.setVisible(true);

                }
            }
        }
    }

    // The action for opening the preferences dialog.
    AbstractAction showPrefs = new ShowPrefsAction();

    class ShowPrefsAction
            extends MnemonicAwareAction {

        public ShowPrefsAction() {
            super(GUIGlobals.getImage("preferences"));
            putValue(NAME, "Preferences");
            putValue(SHORT_DESCRIPTION, Globals.lang("Preferences"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            preferences();
        }
    }

    /**
     * This method does the job of adding imported entries into the active
     * database, or into a new one. It shows the ImportInspectionDialog if
     * preferences indicate it should be used. Otherwise it imports directly.
     *
     * @param panel The BasePanel to add to.
     * @param entries The entries to add.
     * @param filename Name of the file where the import came from.
     * @param openInNew Should the entries be imported into a new database?
     */
    public void addImportedEntries(final BasePanel panel, final List<BibtexEntry> entries,
            String filename, final boolean openInNew) {
        /*
         * Use the import inspection dialog if it is enabled in preferences, and
         * (there are more than one entry or the inspection dialog is also
         * enabled for single entries):
         */
        if (Globals.prefs.getBoolean("useImportInspectionDialog")
                && (Globals.prefs.getBoolean("useImportInspectionDialogForSingle") || (entries.size() > 1))) {
            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    ImportInspectionDialog diag = new ImportInspectionDialog(JabRefFrame.this,
                            panel, BibtexFields.DEFAULT_INSPECTION_FIELDS, Globals.lang("Import"),
                            openInNew);
                    diag.addEntries(entries);
                    diag.entryListComplete();
                    // On the one hand, the following statement could help at issues when JabRef is minimized to the systray
                    // On the other hand, users might dislake modality and this is not required to let the GUI work.
                    // Therefore, it is disabled.
                    // diag.setModal(true);
                    Util.placeDialog(diag, JabRefFrame.this);
                    diag.setVisible(true);
                    diag.toFront();
                }
            });

        } else {
            JabRefFrame.this.addBibEntries(entries, filename, openInNew);
            if ((panel != null) && (entries.size() == 1)) {
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        panel.highlightEntry(entries.get(0));
                    }
                });
            }
        }
    }

    /**
     * Adds the entries to the database, possibly checking for duplicates first.
     *
     * @param bibentries
     * @param filename If non-null, a message is printed to the status line
     * describing how many entries were imported, and from which file. If null,
     * the message will not be printed.
     * @param intoNew Determines if the entries will be put in a new database or
     * in the current one.
     */
    public int addBibEntries(List<BibtexEntry> bibentries, String filename,
            boolean intoNew) {
        if (bibentries == null || bibentries.isEmpty()) {

            // No entries found. We need a message for this.
            JOptionPane.showMessageDialog(JabRefFrame.this, Globals.lang("No entries found. Please make sure you are "
                    + "using the correct import filter."), Globals.lang("Import failed"),
                    JOptionPane.ERROR_MESSAGE);
            return 0;
        }

        int addedEntries = 0;

        // Set owner and timestamp fields:
        Util.setAutomaticFields(bibentries, Globals.prefs.getBoolean("overwriteOwner"),
                Globals.prefs.getBoolean("overwriteTimeStamp"), Globals.prefs.getBoolean("markImportedEntries"));

        if (intoNew || (tabbedPane.getTabCount() == 0)) {
            // Import into new database.
            BibtexDatabase database = new BibtexDatabase();
            for (BibtexEntry entry : bibentries) {
                try {
                    entry.setId(Util.createNeutralId());
                    database.insertEntry(entry);
                } catch (KeyCollisionException ex) {
                    //ignore
                    System.err.println("KeyCollisionException [ addBibEntries(...) ]");
                }
            }
            // Metadata are only put in bibtex files, so we will not find it
            // in imported files. We therefore pass in an empty MetaData:
            BasePanel bp = new BasePanel(JabRefFrame.this, database, null, new MetaData(), Globals.prefs.get("defaultEncoding"));
            /*
            if (prefs.getBoolean("autoComplete")) {
            db.setCompleters(autoCompleters);
            }
             */
            addedEntries = database.getEntryCount();
            tabbedPane.add(GUIGlobals.untitledTitle, bp);
            bp.markBaseChanged();
            tabbedPane.setSelectedComponent(bp);
            if (filename != null) {
                output(Globals.lang("Imported database") + " '" + filename + "' "
                        + Globals.lang("with") + " "
                        + database.getEntryCount() + " "
                        + Globals.lang("entries into new database") + ".");
            }
        } else {
            // Import into current database.
            BasePanel basePanel = basePanel();
            BibtexDatabase database = basePanel.database;
            int oldCount = database.getEntryCount();
            NamedCompound ce = new NamedCompound(Globals.lang("Import entries"));

            mainLoop:
            for (BibtexEntry entry : bibentries) {
                boolean dupli = false;
                // Check for duplicates among the current entries:
                for (String s : database.getKeySet()) {
                    BibtexEntry existingEntry = database.getEntryById(s);
                    if (DuplicateCheck.isDuplicate(entry, existingEntry
                    )) {
                        DuplicateResolverDialog drd = new DuplicateResolverDialog(JabRefFrame.this, existingEntry, entry, DuplicateResolverDialog.IMPORT_CHECK);
                        drd.setVisible(true);
                        int res = drd.getSelected();

                        switch (res) {
                            case DuplicateResolverDialog.KEEP_LOWER:
                                dupli = true;
                                break;

                            case DuplicateResolverDialog.KEEP_UPPER:
                                database.removeEntry(existingEntry.getId());
                                ce.addEdit(new UndoableRemoveEntry(database, existingEntry, basePanel));
                                break;

                            case DuplicateResolverDialog.BREAK:
                                break mainLoop;

                            default:
                                // Handle unexpected values if needed
                                break;
                        }
                    }
                }

                if (!dupli) {
                    try {
                        entry.setId(Util.createNeutralId());
                        database.insertEntry(entry);
                        ce.addEdit(new UndoableInsertEntry(database, entry, basePanel));
                        addedEntries++;
                    } catch (KeyCollisionException ex) {
                        //ignore
                        System.err.println("KeyCollisionException [ addBibEntries(...) ]");
                    }
                }
            }
            if (addedEntries > 0) {
                ce.end();
                basePanel.undoManager.addEdit(ce);
                basePanel.markBaseChanged();
                if (filename != null) {
                    output(Globals.lang("Imported database") + " '" + filename + "' "
                            + Globals.lang("with") + " "
                            + (database.getEntryCount() - oldCount) + " "
                            + Globals.lang("entries into new database") + ".");
                }
            }

        }

        return addedEntries;
    }

    private void setUpImportMenu(JMenu importMenu, boolean intoNew_) {
        final boolean intoNew = intoNew_;
        importMenu.removeAll();

        // Add a menu item for autodetecting import format:
        importMenu.add(new ImportMenuItem(JabRefFrame.this, intoNew));

        // Add custom importers
        importMenu.addSeparator();

        SortedSet<ImportFormat> customImporters = Globals.importFormatReader.getCustomImportFormats();
        JMenu submenu = new JMenu(Globals.lang("Custom importers"));
        submenu.setMnemonic(KeyEvent.VK_S);

        // Put in all formatters registered in ImportFormatReader:
        for (ImportFormat imFo : customImporters) {
            submenu.add(new ImportMenuItem(JabRefFrame.this, intoNew, imFo));
        }

        if (!customImporters.isEmpty()) {
            submenu.addSeparator();
        }

        submenu.add(customImpAction);

        importMenu.add(submenu);
        importMenu.addSeparator();

        // Put in all formatters registered in ImportFormatReader:
        for (ImportFormat imFo : Globals.importFormatReader.getBuiltInInputFormats()) {
            importMenu.add(new ImportMenuItem(JabRefFrame.this, intoNew, imFo));
        }
    }

    public FileHistory getFileHistory() {
        return fileHistory;
    }

    /**
     * Set the preview active state for all BasePanel instances.
     *
     * @param enabled
     */
    public void setPreviewActive(boolean enabled) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            baseAt(i).setPreviewActive(enabled);
        }
    }

    public void removeCachedEntryEditors() {
        for (int j = 0; j < tabbedPane.getTabCount(); j++) {
            BasePanel bp = (BasePanel) tabbedPane.getComponentAt(j);
            bp.entryEditors.clear();
        }
    }

    /**
     * This method shows a wait cursor and blocks all input to the JFrame's
     * contents.
     */
    public void block() {
        getGlassPane().setVisible(true);
    }

    /**
     * This method reverts the cursor to normal, and stops blocking input to the
     * JFrame's contents. There are no adverse effects of calling this method
     * redundantly.
     */
    public void unblock() {
        getGlassPane().setVisible(false);
    }

    /**
     * Set the visibility of the progress bar in the right end of the status
     * line at the bottom of the frame.If not called on the event dispatch
     * thread, this method uses SwingUtilities.invokeLater() to do the actual
     * operation on the EDT.
     *
     * @param visible
     */
    public void setProgressBarVisible(final boolean visible) {
        if (SwingUtilities.isEventDispatchThread()) {
            progressBar.setVisible(visible);
        } else {
            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    progressBar.setVisible(visible);
                }
            });
        }
    }

    /**
     * Sets the current value of the progress bar.If not called on the event
     * dispatch thread, this method uses SwingUtilities.invokeLater() to do the
     * actual operation on the EDT.
     *
     * @param value
     */
    public void setProgressBarValue(final int value) {
        if (SwingUtilities.isEventDispatchThread()) {
            progressBar.setValue(value);
        } else {
            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    progressBar.setValue(value);
                }
            });
        }

    }

    /**
     * Sets the indeterminate status of the progress bar.If not called on the
     * event dispatch thread, this method uses SwingUtilities.invokeLater() to
     * do the actual operation on the EDT.
     *
     * @param value
     */
    public void setProgressBarIndeterminate(final boolean value) {
        if (SwingUtilities.isEventDispatchThread()) {
            progressBar.setIndeterminate(value);
        } else {
            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    progressBar.setIndeterminate(value);
                }
            });
        }

    }

    /**
     * Sets the maximum value of the progress bar.Always call this method before
     * using the progress bar, to set a maximum value appropriate to the task at
     * hand. If not called on the event dispatch thread, this method uses
     * SwingUtilities.invokeLater() to do the actual operation on the EDT.
     *
     * @param value
     */
    public void setProgressBarMaximum(final int value) {
        if (SwingUtilities.isEventDispatchThread()) {
            progressBar.setMaximum(value);
        } else {
            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    progressBar.setMaximum(value);
                }
            });
        }

    }

    class SaveSessionAction
            extends MnemonicAwareAction {

        public SaveSessionAction() {
            super(GUIGlobals.getImage("save"));
            putValue(NAME, "Save session");
            putValue(ACCELERATOR_KEY, prefs.getKey("Save session"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            // Here we store the names of all current files. If
            // there is no current file, we remove any
            // previously stored file name.
            List<String> filenames = new ArrayList<>();
            if (tabbedPane.getTabCount() > 0) {
                for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                    if (tabbedPane.getTitleAt(i).equals(GUIGlobals.untitledTitle)) {
                        tabbedPane.setSelectedIndex(i);
                        int answer = JOptionPane.showConfirmDialog(JabRefFrame.this, Globals.lang("This untitled database must be saved first to be "
                                + "included in the saved session. Save now?"),
                                Globals.lang("Save database"),
                                JOptionPane.YES_NO_OPTION);
                        if (answer == JOptionPane.YES_OPTION) {
                            // The user wants to save.
                            try {
                                basePanel().runCommand("save");
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (baseAt(i).getFile() != null) {
                        filenames.add(baseAt(i).getFile().getPath());
                    }
                }
            }

            if (filenames.isEmpty()) {
                output(Globals.lang("Not saved (empty session)") + ".");
            } else {
                String[] names = new String[filenames.size()];
                for (int i = 0; i < filenames.size(); i++) {
                    names[i] = filenames.get(i);
                }
                prefs.putStringArray("savedSession", names);
                output(Globals.lang("Saved session") + ".");
            }

        }
    }

    class LoadSessionAction
            extends MnemonicAwareAction {

        boolean running = false;

        public LoadSessionAction() {
            super(GUIGlobals.getImage("loadSession"));
            putValue(NAME, "Load session");
            putValue(ACCELERATOR_KEY, prefs.getKey("Load session"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (prefs.get("savedSession") == null) {
                output(Globals.lang("No saved session found."));
                return;
            }
            if (running) {
                return;
            } else {
                running = true;
            }
            output(Globals.lang("Loading session..."));
            (new Thread() {

                @Override
                public void run() {
                    HashSet<String> currentFiles = new HashSet<>();
                    if (tabbedPane.getTabCount() > 0) {
                        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                            if (baseAt(i).getFile() != null) {
                                currentFiles.add(baseAt(i).getFile().getPath());
                            }
                        }
                    }
                    int i0 = tabbedPane.getTabCount();
                    String[] names = prefs.getStringArray("savedSession");
                    for (int i = 0; i < names.length; i++) {
                        if (!currentFiles.contains(names[i])) {
                            File file = new File(names[i]);
                            if (file.exists()) {
                                //Util.pr("Opening last edited file:"
                                //+fileToOpen.getName());
                                open.openIt(file, i == 0);
                            }
                        }
                    }
                    output(Globals.lang("Files opened") + ": "
                            + (tabbedPane.getTabCount() - i0));
                    running = false;
                }
            }).start();

        }
    }

    class ChangeTabAction
            extends MnemonicAwareAction {

        private final boolean next;

        public ChangeTabAction(boolean next) {
            putValue(NAME, next ? "Next tab" : "Previous tab");
            this.next = next;
            //Util.pr(""+prefs.getKey("Next tab"));
            putValue(ACCELERATOR_KEY,
                    (next ? prefs.getKey("Next tab") : prefs.getKey("Previous tab")));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int i = tabbedPane.getSelectedIndex();
            int newI = (next ? i + 1 : i - 1);
            if (newI < 0) {
                newI = tabbedPane.getTabCount() - 1;
            }
            if (newI == tabbedPane.getTabCount()) {
                newI = 0;
            }
            tabbedPane.setSelectedIndex(newI);
        }
    }

    /**
     * Class for handling general actions; cut, copy and paste. The focused
     * component is kept track of by Globals.focusListener, and we call the
     * action stored under the relevant name in its action map.
     */
    class EditAction
            extends MnemonicAwareAction {

        private final String command;

        public EditAction(String command, URL icon) {
            super(new ImageIcon(icon));
            this.command = command;
            String nName = Util.nCase(command);
            putValue(NAME, nName);
            putValue(ACCELERATOR_KEY, prefs.getKey(nName));
            putValue(SHORT_DESCRIPTION, Globals.lang(nName));
            //putValue(ACCELERATOR_KEY,
            //         (next?prefs.getKey("Next tab"):prefs.getKey("Previous tab")));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            //Util.pr(Globals.focusListener.getFocused().toString());
            JComponent source = Globals.focusListener.getFocused();
            try {
                source.getActionMap().get(command).actionPerformed(new ActionEvent(source, 0, command));
            } catch (NullPointerException ex) {
                // No component is focused, so we do nothing.
            }
        }
    }

    class CustomizeExportsAction extends MnemonicAwareAction {

        public CustomizeExportsAction() {
            putValue(NAME, "Manage custom exports");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            ExportCustomizationDialog ecd = new ExportCustomizationDialog(JabRefFrame.this);
            ecd.setVisible(true);
        }
    }

    class CustomizeImportsAction extends MnemonicAwareAction {

        public CustomizeImportsAction() {
            putValue(NAME, "Manage custom imports");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            ImportCustomizationDialog ecd = new ImportCustomizationDialog(JabRefFrame.this);
            ecd.setVisible(true);
        }
    }

    class CustomizeEntryTypeAction extends MnemonicAwareAction {

        public CustomizeEntryTypeAction() {
            putValue(NAME, "Customize entry types");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            JDialog dl = new EntryCustomizationDialog2(JabRefFrame.this);
            Util.placeDialog(dl, JabRefFrame.this);
            dl.setVisible(true);
        }
    }

    class GenFieldsCustomizationAction extends MnemonicAwareAction {

        public GenFieldsCustomizationAction() {
            putValue(NAME, "Set up general fields");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            GenFieldsCustomizer gf = new GenFieldsCustomizer(JabRefFrame.this);
            Util.placeDialog(gf, JabRefFrame.this);
            gf.setVisible(true);

        }
    }

    class DatabasePropertiesAction extends MnemonicAwareAction {

        DatabasePropertiesDialog propertiesDialog = null;

        public DatabasePropertiesAction() {
            putValue(NAME, "Database properties");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (propertiesDialog == null) {
                propertiesDialog = new DatabasePropertiesDialog(JabRefFrame.this);
            }
            propertiesDialog.setPanel(basePanel());
            Util.placeDialog(propertiesDialog, JabRefFrame.this);
            propertiesDialog.setVisible(true);
        }

    }

    class BibtexKeyPatternAction extends MnemonicAwareAction {

        BibtexKeyPatternDialog bibtexKeyPatternDialog = null;

        public BibtexKeyPatternAction() {
            putValue(NAME, "Bibtex key patterns");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            JabRefPreferences.getInstance();
            if (bibtexKeyPatternDialog == null) {
                // if no instance of BibtexKeyPatternDialog exists, create new one
                bibtexKeyPatternDialog = new BibtexKeyPatternDialog(JabRefFrame.this, basePanel());
            } else {
                // BibtexKeyPatternDialog allows for updating content based on currently selected panel
                bibtexKeyPatternDialog.setPanel(basePanel());
            }
            Util.placeDialog(bibtexKeyPatternDialog, JabRefFrame.this);
            bibtexKeyPatternDialog.setVisible(true);
        }

    }

    class IncreaseTableFontSizeAction extends MnemonicAwareAction {

        public IncreaseTableFontSizeAction() {
            putValue(NAME, "Increase table font size");
            putValue(ACCELERATOR_KEY, Globals.prefs.getKey("Increase table font size"));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            int currentSize = GUIGlobals.CURRENTFONT.getSize();
            GUIGlobals.CURRENTFONT = new Font(GUIGlobals.CURRENTFONT.getFamily(), GUIGlobals.CURRENTFONT.getStyle(),
                    currentSize + 1);
            Globals.prefs.putInt("fontSize", currentSize + 1);
            for (int i = 0; i < baseCount(); i++) {
                baseAt(i).updateTableFont();
            }
        }
    }

    class DecreaseTableFontSizeAction extends MnemonicAwareAction {

        public DecreaseTableFontSizeAction() {
            putValue(NAME, "Decrease table font size");
            putValue(ACCELERATOR_KEY, Globals.prefs.getKey("Decrease table font size"));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            int currentSize = GUIGlobals.CURRENTFONT.getSize();
            if (currentSize < 2) {
                return;
            }
            GUIGlobals.CURRENTFONT = new Font(GUIGlobals.CURRENTFONT.getFamily(), GUIGlobals.CURRENTFONT.getStyle(),
                    currentSize - 1);
            Globals.prefs.putInt("fontSize", currentSize - 1);
            for (int i = 0; i < baseCount(); i++) {
                baseAt(i).updateTableFont();
            }
        }
    }

    class MinimizeToSysTrayAction extends MnemonicAwareAction {

        public MinimizeToSysTrayAction() {
            putValue(NAME, "Minimize to system tray");
            putValue(ACCELERATOR_KEY, Globals.prefs.getKey("Minimize to system tray"));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            if (sysTray == null) {
                sysTray = new SysTray(JabRefFrame.this);
            }
            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    sysTray.setTrayIconVisible(true);
                    JabRefFrame.this.setVisible(false);
                }
            });
        }
    }

    public void showIfMinimizedToSysTray() {
        // TODO: does not work correctly when a dialog is shown
        // Workaround: put into invokeLater queue before a dialog is added to that queue
        if (!this.isVisible()) {
            // isVisible() is false if minimized to systray
            if (sysTray != null) {
                sysTray.setTrayIconVisible(false);
            }
            setVisible(true);
            this.isActive();
            toFront();
        }
    }

    /*private class ForegroundLabel extends JLabel {
         public ForegroundLabel(String s) {
             super(s);
             setFont(new Font("plain", Font.BOLD, 70));
             setHorizontalAlignment(JLabel.CENTER);
         }

        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            super.paint(g2);    //To change body of overridden methods use File | Settings | File Templates.
        }
    }       */
    private class MyGlassPane extends JPanel {
        //ForegroundLabel infoLabel = new ForegroundLabel("Showing search");

        public MyGlassPane() {
            addKeyListener(new KeyAdapter() {
            });
            addMouseListener(new MouseAdapter() {
            });
            /*  infoLabel.setForeground(new Color(255, 100, 100, 124));

        setLayout(new BorderLayout());
        add(infoLabel, BorderLayout.CENTER);*/
            super.setCursor(
                    Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }
        // Override isOpaque() to prevent the glasspane from hiding the window contents:

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    @Override
    public void showMessage(Object message, String title, int msgType) {
        JOptionPane.showMessageDialog(this, message, title, msgType);
    }

    @Override
    public void setStatus(String s) {
        output(s);
    }

    @Override
    public void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message);
    }

    public SearchManager2 getSearchManager() {
        return searchManager;
    }

    /**
     * Updates all UI components to reflect theme changes. This should be called
     * when the application theme is changed.
     */
    public void updateUIForThemeChange() {

        // Update the frame and its direct components
        SwingUtilities.updateComponentTreeUI(this);

        // Update tabbed pane
        if (tabbedPane != null) {
            tabbedPane.updateUI();
            tabbedPane.setBorder(null);
            // SwingUtilities.updateComponentTreeUI(tabbedPane);
        }

        // Update toolbar
        if (tlb != null) {
            SwingUtilities.updateComponentTreeUI(tlb);
            // Recreate toolbar icons to match new theme
            refreshToolbarIcons();
        }

        // Update menu bar
        if (mb != null) {
            SwingUtilities.updateComponentTreeUI(mb);
        }

        // Update side pane
        if (sidePaneManager != null) {
            sidePaneManager.updateUIForThemeChange();
        }

        // Update status components
        if (statusLine != null) {
            SwingUtilities.updateComponentTreeUI(statusLine);
        }
        if (statusLabel != null) {
            SwingUtilities.updateComponentTreeUI(statusLabel);
        }
        if (progressBar != null) {
            SwingUtilities.updateComponentTreeUI(progressBar);
        }

        // Update all open base panels (tabs)
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            BasePanel bp = baseAt(i);
            if (bp != null) {
                bp.rebuildAllEntryEditors();
            }
        }
        // Update help dialog if open
        if (helpDiag != null && helpDiag.isVisible()) {
            SwingUtilities.updateComponentTreeUI(helpDiag);
        }

        // Update preferences dialog if open
        if (prefsDialog != null && prefsDialog.isVisible()) {
            SwingUtilities.updateComponentTreeUI(prefsDialog);
        }

        // Force revalidation and repaint
        revalidate();
        repaint();
    }

}
