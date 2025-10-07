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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

import net.sf.jabref.external.ExternalFileType;

import org.xnap.commons.gui.shortcut.EmacsKeyBindings;

import net.sf.jabref.specialfields.Printed;
import net.sf.jabref.specialfields.Priority;
import net.sf.jabref.specialfields.Quality;
import net.sf.jabref.specialfields.Rank;
import net.sf.jabref.specialfields.ReadStatus;
import net.sf.jabref.specialfields.Relevance;
import net.sf.jabref.specialfields.SpecialFieldsUtils;

// new loading
import java.awt.*;
import javax.swing.*;

import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.image.BufferedImage;
import net.sf.jabref.gui.ThemeAwareComponent;

/**
 * Static variables for graphics files and keyboard shortcuts.
 */
public class GUIGlobals implements ThemeAwareComponent {

    // Frame titles.
    public static String frameTitle = "JabRef",
            version = Globals.VERSION,
            stringsTitle = "Strings for database",
            //untitledStringsTitle = stringsTitle + Globals.lang("untitled"),
            untitledTitle = "untitled",
            helpTitle = "JabRef help",
            TYPE_HEADER = "entrytype",
            NUMBER_COL = "#",
            encPrefix = "Encoding: "; // Part of the signature in written bib files.

    public static Font CURRENTFONT,
            typeNameFont,
            jabRefFont,
            fieldNameFont;

    // Signature written at the top of the .bib file.
    public static final String SIGNATURE
            = "This file was created with JabRef";

    // Size of help window.
    public static final Dimension helpSize = new Dimension(700, 600),
            aboutSize = new Dimension(600, 265),
            searchPaneSize = new Dimension(430, 70),
            searchFieldSize = new Dimension(215, 25);
    public static Double zoomLevel = 1.0;

    // Divider size for BaseFrame split pane. 0 means non-resizable.
    public static final int SPLIT_PANE_DIVIDER_SIZE = 4,
            SPLIT_PANE_DIVIDER_LOCATION = 145 + 15, // + 15 for possible scrollbar.
            TABLE_ROW_PADDING = 8,
            KEYBIND_COL_0 = 200,
            KEYBIND_COL_1 = 80, // Added to the font size when determining table
            MAX_CONTENT_SELECTOR_WIDTH = 240; // The max width of the combobox for content selectors.

    // File names.
    public static String //configFile = "preferences.dat",
            backupExt = ".bak";

    // Image paths.
    public static String imageSize = "24",
            extension = ".gif",
            ex = imageSize + extension,
            pre = "/images/",
            helpPre = "/help/",
            fontPath = "/images/font/";

    static HashMap<String, JLabel> tableIcons = new HashMap<String, JLabel>(); // Contains table icon mappings. Set up
    // further below.
//    public static Color activeEditor = new Color(230, 230, 255);

    static HashMap<String, String> iconMap;

    public static JLabel getTableIcon(String fieldType) {
        // If tableIcons is null or doesn't contain the icon, reinitialize
        if (tableIcons == null || !tableIcons.containsKey(fieldType)) {
            initTableIcons();
        }

        Object o = tableIcons.get(fieldType);
        if (o == null) {
            // Try to reinitialize and get again
            initTableIcons();
            o = tableIcons.get(fieldType);
            if (o == null) {
                Globals.logger("Error: no table icon defined for type '" + fieldType + "'.");
                return null;
            }
        }
        return (JLabel) o;
    }

    //**************************************************************************
    //**************************************************************************
    // Define standard icon sizes
    public static final int TOOLBAR_ICON_SMALL = 16;
    public static final int TOOLBAR_ICON_MEDIUM = 24;
    public static final int TOOLBAR_ICON_LARGE = 32;
    // public static final int TOOLBAR_ICON_SIZE = 32;
    public static int CURRENT_TOOLBAR_ICON_SIZE = TOOLBAR_ICON_MEDIUM; // Default

    public static final int MENU_ICON_SIZE = 16;       // Menu icons: 16x16  
    public static final int WINDOW_ICON_SIZE = 48;     // Window icons: 48x48
    public static final int TABLE_ICON_SIZE = 16;      // Table icons: 16x16

    /**
     * MAIN METHOD: Get Icon for general UI use (preferred for buttons, menus,
     * etc.) Supports both SVG and legacy PNG/GIF icons
     */
    /**
     * Get icon with automatic size detection based on context
     */
    public static Icon getIcon(String name) {
        // Default size (will be overridden by specific methods)
        return getIcon(name, MENU_ICON_SIZE, MENU_ICON_SIZE);
    }

    /**
     * Get icon for toolbar
     */
    public static Icon getToolbarIcon(String name) {
        return getIcon(name, CURRENT_TOOLBAR_ICON_SIZE, CURRENT_TOOLBAR_ICON_SIZE);
    }

    /**
     * Get icon for menus (16x16)
     */
    public static Icon getMenuIcon(String name) {
        return getIcon(name, MENU_ICON_SIZE, MENU_ICON_SIZE);
    }

    /**
     * Get icon for tables (16x16)
     */
//    public static Icon getTableIcon(String name) {
//        return getIcon(name, TABLE_ICON_SIZE, TABLE_ICON_SIZE);
//    }
    /**
     * Get icon for window title bar (48x48)
     */
    public static Icon getWindowIcon(String name) {
        return getIcon(name, WINDOW_ICON_SIZE, WINDOW_ICON_SIZE);
    }

    /**
     * Get Icon with custom dimensions - FIXED PATH RESOLUTION Safe method to
     * get Icon - handles missing SVG files properly
     */
    public static Icon getIcon(String name, int width, int height) {
        // First, try to get the PNG path from the properties mapping
        String pngPath = iconMap.get(name);
        if (pngPath == null) {
            // System.err.println("No mapping found for icon: " + name);
            return getLegacyIcon(name);
        }

        // Try SVG: replace .png with .svg in the path
        String svgPath = pngPath.replace(".png", ".svg");
        URL svgUrl = GUIGlobals.class.getResource(svgPath);

        if (svgUrl != null) {
            try {
                FlatSVGIcon icon = new FlatSVGIcon(svgUrl);
                // Test if the SVG can be loaded without throwing NPE
                icon.getIconWidth(); // This will throw if SVG is invalid
                return icon.derive(width, height);
            } catch (Exception e) {
                System.err.println("SVG icon loading failed for " + name + ": " + e.getMessage());
                // Fall through to PNG
            }
        }

        // SVG not available or failed, use PNG
        return getLegacyIcon(name);
    }

    /**
     * Get ImageIcon for cases where ImageIcon is specifically needed (maintains
     * backward compatibility)
     */
    public static ImageIcon getImageIcon(String name) {
        Icon icon = getIcon(name);
        if (icon instanceof ImageIcon) {
            return (ImageIcon) icon;
        } else if (icon != null) {
            // Convert any Icon to ImageIcon
            Image image = iconToImage(icon);
            return image != null ? new ImageIcon(image) : null;
        }
        return null;
    }

    /**
     * Safe method to get Image for window icons
     */
    public static Image getImageAsImage(String name) {
        // For window icons, prefer PNG to avoid SVG conversion issues
        ImageIcon pngIcon = getLegacyIcon(name);
        if (pngIcon != null) {
            return pngIcon.getImage();
        }

        // Fallback: try SVG if PNG not found
        try {
            Icon icon = getIcon(name, 48, 48);
            if (icon != null) {
                return iconToImage(icon);
            }
        } catch (Exception e) {
            System.err.println("Failed to convert icon to image: " + e.getMessage());
        }

        return null;
    }

    /**
     * LEGACY SUPPORT: Maintain backward compatibility for existing code
     *
     * @deprecated Use getIcon() for general UI components or getImageIcon() for
     * ImageIcon-specific cases
     */
    @Deprecated
    public static ImageIcon getImage(String name) {
        return getImageIcon(name);
    }

    /**
     * Safe icon to image conversion
     */
    public static Image iconToImage(Icon icon) {
        if (icon == null) {
            return null;
        }

        try {
            int width = icon.getIconWidth();
            int height = icon.getIconHeight();

            // Handle cases where icon dimensions might be invalid
            if (width <= 0) {
                width = 48;
            }
            if (height <= 0) {
                height = 48;
            }

            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsConfiguration gc = ge.getDefaultScreenDevice().getDefaultConfiguration();
            BufferedImage image = gc.createCompatibleImage(width, height, Transparency.TRANSLUCENT);
            Graphics2D g = image.createGraphics();

            // Try to paint the icon - catch any exceptions during rendering
            try {
                icon.paintIcon(null, g, 0, 0);
            } catch (Exception e) {
                System.err.println("Error painting icon: " + e.getMessage());
                // Draw a placeholder instead of failing completely
                g.setColor(Color.RED);
                g.fillRect(0, 0, width, height);
                g.setColor(Color.WHITE);
                g.drawString("Icon Error", 5, height / 2);
            } finally {
                g.dispose();
            }

            return image;
        } catch (Exception e) {
            System.err.println("Error converting icon to image: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get legacy PNG/GIF icon with proper error handling
     */
    private static ImageIcon getLegacyIcon(String name) {
        try {
            String path = iconMap.get(name);
            if (path == null) {
                // System.err.println("No icon mapping for: " + name);
                return null;
            }

            URL url = GUIGlobals.class.getResource(path);
            if (url == null) {
                // System.err.println("Icon resource not found: " + path);
                return null;
            }

            return new ImageIcon(url);
        } catch (Exception e) {
            System.err.println("Error loading legacy icon " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get icon specifically for toolbar (24x24) without affecting the Action
     */
    public static Icon getToolbarIconOnly(String name) {
        return getIcon(name, CURRENT_TOOLBAR_ICON_SIZE, CURRENT_TOOLBAR_ICON_SIZE);
    }

    /**
     * Get icon specifically for menus (16x16) without affecting the Action
     */
    public static Icon getMenuIconOnly(String name) {
        return getIcon(name, MENU_ICON_SIZE, MENU_ICON_SIZE);
    }

    //**************************************************************************
    //Help files (in HTML format):
    public static final String baseFrameHelp = "BaseFrameHelp.html",
            entryEditorHelp = "EntryEditorHelp.html",
            stringEditorHelp = "StringEditorHelp.html",
            helpContents = "Contents.html",
            searchHelp = "SearchHelp.html",
            groupsHelp = "GroupsHelp.html",
            customEntriesHelp = "CustomEntriesHelp.html",
            contentSelectorHelp = "ContentSelectorHelp.html",
            specialFieldsHelp = "SpecialFieldsHelp.html",
            labelPatternHelp = "LabelPatterns.html",
            ownerHelp = "OwnerHelp.html",
            timeStampHelp = "TimeStampHelp.html",
            pdfHelp = "ExternalFiles.html",
            exportCustomizationHelp = "CustomExports.html",
            importCustomizationHelp = "CustomImports.html",
            medlineHelp = "MedlineHelp.html",
            citeSeerHelp = "CiteSeerHelp.html",
            generalFieldsHelp = "GeneralFields.html",
            aboutPage = "About.html",
            shortPlainImport = "ShortPlainImport.html",
            importInspectionHelp = "ImportInspectionDialog.html",
            shortIntegrityCheck = "ShortIntegrityCheck.html",
            remoteHelp = "RemoteHelp.html",
            journalAbbrHelp = "JournalAbbreviations.html",
            regularExpressionSearchHelp = "ExternalFiles.html#RegularExpressionSearch",
            nameFormatterHelp = "CustomExports.html#NameFormatter",
            previewHelp = "PreviewHelp.html",
            pluginHelp = "Plugin.html",
            autosaveHelp = "Autosave.html";

    //	Colors.
    //    public static Color lightGray = new Color(230, 30, 30), // Light gray background
    //            entryEditorLabelColor = new Color(100, 100, 150), // Empty field, blue.
    //            nullFieldColor = new Color(75, 130, 95), // Valid field, green.
    //            gradientGray = new Color(112, 121, 165), // Title bar gradient color, sidepaneheader
    //            gradientBlue = new Color(0, 27, 102), // Title bar gradient color, sidepaneheader
    //            //activeTabbed = Color.black,  // active Database (JTabbedPane)
    //            //inActiveTabbed = Color.gray.darker(),  // inactive Database
    //            activeTabbed = entryEditorLabelColor.darker(), // active Database (JTabbedPane)
    //            inActiveTabbed = Color.black, // inactive Database
    //            infoField = new Color(254, 255, 225) // color for an info field
    //            ;
    //    public static final Color lightGray = new Color(245, 246, 248); // neutral light background
    //    public static final Color entryEditorLabelColor = new Color(96, 110, 140); // muted blue-gray for labels
    //    public static final Color nullFieldColor = new Color(56, 158, 115);    // fresh but subdued green
    //    public static final Color gradientGray = new Color(164, 172, 189);   // lighter neutral for gradients
    //    public static final Color gradientBlue = new Color(42, 75, 145);     // modern desaturated blue
    //    public static final Color activeTabbed = new Color(96, 110, 140);    // same as label; keeps consistency
    //    public static final Color inActiveTabbed = new Color(130, 130, 130);   // neutral gray for inactive
    //    public static final Color infoField = new Color(250, 252, 240);   // very light yellow-green tint
    //    public static final Color activeTabbedTitle = new Color(0, 0, 0);       // strong black text for selected tab
    //    public static final Color inactiveTabbedTitle = new Color(96, 110, 140);  // muted blue-gray text for other tabs
//    public static final Color activeTabbedTitle = UIManager.getColor("TabbedPane.selectedForeground");
//    public static final Color inactiveTabbedTitle = UIManager.getColor("TabbedPane.foreground");
//    public static final Color gradientBlue = UIManager.getColor("Component.accentColor");
//    public static final Color gradientGray = UIManager.getColor("Component.borderColor");
//    public static final Color lightGray = UIManager.getColor("Panel.background");
//    public static final Color entryEditorLabelColor = UIManager.getColor("Label.foreground");
//    public static final Color nullFieldColor = UIManager.getColor("Component.accentColor"); // FlatLaf 3.2+
//    public static final Color activeTabbed = UIManager.getColor("TabbedPane.selectedBackground");
//    public static final Color inActiveTabbed = UIManager.getColor("TabbedPane.background");
//    public static final Color infoField = UIManager.getColor("Component.innerFocusColor");
    public static Color editorTextColor = null, validFieldBackgroundColor = null,
            activeBackground = null, invalidFieldBackgroundColor = null;

    public static String META_FLAG = "jabref-meta: ";
    public static String META_FLAG_OLD = "bibkeeper-meta: ";
    public static String ENTRYTYPE_FLAG = "jabref-entrytype: ";

    // some fieldname constants
    public static final double DEFAULT_FIELD_WEIGHT = 1,
            MAX_FIELD_WEIGHT = 2;

    // constants for editor types:
    public static final int STANDARD_EDITOR = 1,
            FILE_LIST_EDITOR = 2;

    public static final int MAX_BACK_HISTORY_SIZE = 10; // The maximum number of "Back" operations stored.

    public static final String FILE_FIELD = "file";

    public static final String FOLDER_FIELD = "folder";

    public static final double SMALL_W = 0.30,
            MEDIUM_W = 0.5,
            LARGE_W = 1.5;

    public static final double PE_HEIGHT = 2;

//	Size constants for EntryTypeForm; small, medium and large.
    public static int[] FORM_WIDTH = new int[]{500, 650, 820};
    public static int[] FORM_HEIGHT = new int[]{90, 110, 130};

//	Constants controlling formatted bibtex output.
    public static final int INDENT = 4,
            LINE_LENGTH = 65; // Maximum

    public static int DEFAULT_FIELD_LENGTH = 100,
            NUMBER_COL_LENGTH = 32,
            WIDTH_ICON_COL_RANKING = 35, // Width of Ranking Icon Column
            WIDTH_ICON_COL = 19;

    // Column widths for export customization dialog table:
    public static final int EXPORT_DIALOG_COL_0_WIDTH = 50,
            EXPORT_DIALOG_COL_1_WIDTH = 200,
            EXPORT_DIALOG_COL_2_WIDTH = 30;

    // Column widths for import customization dialog table:
    public static final int IMPORT_DIALOG_COL_0_WIDTH = 200,
            IMPORT_DIALOG_COL_1_WIDTH = 80,
            IMPORT_DIALOG_COL_2_WIDTH = 200,
            IMPORT_DIALOG_COL_3_WIDTH = 200;

    public static final Map<String, String> LANGUAGES;

    static {
        LANGUAGES = new TreeMap<String, String>();

        // LANGUAGES contains mappings for supported languages.
        LANGUAGES.put("English", "en");
        LANGUAGES.put("Dansk", "da");
        LANGUAGES.put("Deutsch", "de");
        LANGUAGES.put("Fran\u00E7ais", "fr");
        LANGUAGES.put("Italiano", "it");
        LANGUAGES.put("Japanese", "ja");
        LANGUAGES.put("Nederlands", "nl");
        LANGUAGES.put("Norsk", "no");
        LANGUAGES.put("Español", "es");
        //LANGUAGES.put("Polski", "pl");
        LANGUAGES.put("Turkish", "tr");
        LANGUAGES.put("Simplified Chinese", "zh");
        LANGUAGES.put("Vietnamese", "vi");
        LANGUAGES.put("Bahasa Indonesia", "in");
        LANGUAGES.put("Brazilian Portugese", "pt_BR");
        LANGUAGES.put("Russian", "ru");

        // Set up entry editor colors, first time:
        // updateEntryEditorColors();
        // Register for theme changes
        ThemeWatcher.register(new GUIGlobals());
    }

    @Override
    public void onThemeChanged() {
        // Clear the icon cache so icons are reloaded with the new theme
        clearTableIconCache();

        // Reinitialize table icons with new theme
        initTableIcons();
    }

    /**
     * Clear the table icon cache to force reloading with new theme
     */
    public static void clearTableIconCache() {
        if (tableIcons != null) {
            tableIcons.clear();
        }
    }

    /**
     * Reinitialize table icons with current theme
     */
    public static void initTableIcons() {
        if (tableIcons == null) {
            tableIcons = new HashMap<String, JLabel>();
        }

        JLabel lab;
        lab = new JLabel(getIcon("pdfSmall"));
        lab.setToolTipText(Globals.lang("Open") + " PDF");
        tableIcons.put("pdf", lab);

        lab = new JLabel(getIcon("wwwSmall"));
        lab.setToolTipText(Globals.lang("Open") + " URL");
        tableIcons.put("url", lab);

        lab = new JLabel(getIcon("citeseer"));
        lab.setToolTipText(Globals.lang("Open") + " CiteSeer URL");
        tableIcons.put("citeseerurl", lab);

        lab = new JLabel(getIcon("arxiv"));
        lab.setToolTipText(Globals.lang("Open") + " ArXiv URL");
        tableIcons.put("eprint", lab);

        lab = new JLabel(getIcon("doiSmall"));
        lab.setToolTipText(Globals.lang("Open") + " DOI " + Globals.lang("web link"));
        tableIcons.put("doi", lab);

        lab = new JLabel(getIcon("psSmall"));
        lab.setToolTipText(Globals.lang("Open") + " PS");
        tableIcons.put("ps", lab);

        lab = new JLabel(getIcon("psSmall"));
        lab.setToolTipText(Globals.lang("Open folder"));
        tableIcons.put(GUIGlobals.FOLDER_FIELD, lab);

        lab = new JLabel(getIcon("psSmall"));
        lab.setToolTipText(Globals.lang("Open file"));
        tableIcons.put(GUIGlobals.FILE_FIELD, lab);

        // Update external file type icons
        for (ExternalFileType fileType : Globals.prefs.getExternalFileTypeSelection()) {
            lab = new JLabel(fileType.getIcon());
            lab.setToolTipText(Globals.lang("Open " + fileType.getName() + " file"));
            tableIcons.put(fileType.getName(), lab);
        }

        // Update special field icons
        updateSpecialFieldIcons();
    }

    /**
     * Update special field icons with current theme
     */
    private static void updateSpecialFieldIcons() {
        JLabel lab;

        lab = new JLabel(Relevance.getInstance().getRepresentingIcon());
        lab.setToolTipText(Relevance.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_RELEVANCE, lab);

        lab = new JLabel(Quality.getInstance().getRepresentingIcon());
        lab.setToolTipText(Quality.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_QUALITY, lab);

        lab = new JLabel(Rank.getInstance().getRepresentingIcon());
        lab.setToolTipText(Rank.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_RANKING, lab);

        lab = new JLabel(Priority.getInstance().getRepresentingIcon());
        lab.setToolTipText(Priority.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_PRIORITY, lab);

        lab = new JLabel(ReadStatus.getInstance().getRepresentingIcon());
        lab.setToolTipText(ReadStatus.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_READ, lab);

        lab = new JLabel(Printed.getInstance().getRepresentingIcon());
        lab.setToolTipText(Printed.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_PRINTED, lab);
    }

    public static Color getColorSafely(String key, Color defaultColor) {
        Color color = UIManager.getColor(key);
        return color != null ? color : defaultColor;
    }

    public static void updateEntryEditorColors() {
        activeBackground = JabRefPreferences.getInstance().getColor("activeFieldEditorBackgroundColor");
        validFieldBackgroundColor = JabRefPreferences.getInstance().getColor("validFieldBackgroundColor");
        invalidFieldBackgroundColor = JabRefPreferences.getInstance().getColor("invalidFieldBackgroundColor");
        editorTextColor = JabRefPreferences.getInstance().getColor("fieldEditorTextColor");
    }

    /**
     * Read either the default icon theme, or a custom one. If loading of the
     * custom theme fails, try to fall back on the default theme.
     */
    public static void setUpIconTheme() {
        String prefLnF = Globals.prefs.get("Theme");

        String iconTheme = prefLnF.toLowerCase().contains("dark")
                || prefLnF.toLowerCase().contains("carbon") ? "dark" : "light";

        setUpIconTheme(iconTheme); // Default to basic if no input
    }

    public static void setUpIconTheme(String iconTheme) {
        String defaultPrefix, prefix;

        // Determine theme based on input
        switch (iconTheme.toLowerCase()) {
            case "dark":
                defaultPrefix = "/images/colibre_dark/";
                break;
            case "light":
            default:
                defaultPrefix = "/images/colibre/";
                break;
        }
        prefix = defaultPrefix;
        URL defaultResource = GUIGlobals.class.getResource(prefix + "Icons.properties");
        URL resource = defaultResource;
        // System.out.println( resource );

        if (Globals.prefs.getBoolean("useCustomIconTheme")) {
            String filename = Globals.prefs.get("customIconThemeFile");
            if (filename != null)
				try {
                File file = new File(filename);
                String parent = file.getParentFile().getAbsolutePath();
                prefix = "file://" + parent + System.getProperty("file.separator");
                resource = new URL("file://" + file.getAbsolutePath());
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        try {
            iconMap = readIconThemeFile(resource, prefix);
            // System.out.println("readIconThemeFile: " + iconMap);
        } catch (IOException e) {
            System.err.println(Globals.lang("Unable to read icon theme file") + " '"
                    + resource.toString() + "'");
            // If we were trying to load a custom theme, try the default one as a fallback:
            if (resource != defaultResource)
				try {
                iconMap = readIconThemeFile(defaultResource, defaultPrefix);
            } catch (IOException e2) {
                System.err.println(Globals.lang("Unable to read default icon theme."));
            }
        }

    }

    /**
     * Looks up the URL for the image representing the given function, in the
     * resource file listing images.
     *
     * @param name The name of the icon, such as "open", "save", "saveAs" etc.
     * @return The URL to the actual image to use.
     */
    public static URL getIconUrl(String name) {
        if (iconMap.containsKey(name)) {
            String path = iconMap.get(name);
            URL url = GUIGlobals.class.getResource(path);
            if (url == null)
				// This may be a resource outside of the jar file, so we try a general URL:
				try {
                url = new URL(path);
            } catch (MalformedURLException ignored) {
            }
            if (url == null) {
                System.err.println(Globals.lang("Could not find image file") + " '" + path + "'");
            }
            return url;
        } else {
            return null;
        }
    }

    /**
     * Constructs an ImageIcon for the given function, using the image specified
     * in the resource files resource/Icons_en.properties.
     *
     * @param name The name of the icon, such as "open", "save", "saveAs" etc.
     * @return The ImageIcon for the function.
     */
//    public static ImageIcon getImage(String name) {
//        URL u = getIconUrl(name);
//        return u != null ? new ImageIcon(getIconUrl(name)) : null;
//    }
    /**
     * Get a Map of all application icons mapped from their keys.
     *
     * @return A Map containing all icons used in the application.
     */
    public static Map<String, String> getAllIcons() {
        return Collections.unmodifiableMap(iconMap);
    }

    /**
     * Read a typical java property file into a HashMap. Currently doesn't
     * support escaping of the '=' character - it simply looks for the first '='
     * to determine where the key ends. Both the key and the value is trimmed
     * for whitespace at the ends.
     *
     * @param file The URL to read information from.
     * @param prefix A String to prefix to all values read. Can represent e.g.
     * the directory where icon files are to be found.
     * @return A HashMap containing all key-value pairs found.
     * @throws IOException
     */
    private static HashMap<String, String> readIconThemeFile(URL file, String prefix) throws IOException {
        HashMap<String, String> map = new HashMap<String, String>();
        InputStream in = null;
        try {
            in = file.openStream();
            StringBuilder buffer = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                buffer.append((char) c);
            }
            String[] lines = buffer.toString().split("\n");
            for (String line1 : lines) {
                String line = line1.trim();
                int index = line.indexOf("=");
                if (index >= 0) {
                    String key = line.substring(0, index).trim();
                    String value = prefix + line.substring(index + 1).trim();
                    map.put(key, value);
                }
            }
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return map;
    }

    /**
     * returns the path to language independent help files
     */
    public static String getLocaleHelpPath() {
        JabRefPreferences prefs = JabRefPreferences.getInstance();
        String middle = prefs.get("language") + "/";
        if (middle.equals("en/")) {
            middle = ""; // english in base help dir.
        }
        return (helpPre + middle);
    }

    /**
     * Perform initializations that are only used in graphical mode. This is to
     * prevent the "Xlib: connection to ":0.0" refused by server" error when
     * access to the X server on Un*x is unavailable.
     */
    public static void init() {
        typeNameFont = new Font("dialog", Font.ITALIC + Font.BOLD, 18);
        fieldNameFont = new Font("arial", Font.ITALIC + Font.BOLD, 14);
        JLabel lab;
        lab = new JLabel(getIcon("pdfSmall"));
        lab.setToolTipText(Globals.lang("Open") + " PDF");
        tableIcons.put("pdf", lab);
        lab = new JLabel(getIcon("wwwSmall"));
        lab.setToolTipText(Globals.lang("Open") + " URL");
        tableIcons.put("url", lab);
        lab = new JLabel(getIcon("citeseer"));
        lab.setToolTipText(Globals.lang("Open") + " CiteSeer URL");
        tableIcons.put("citeseerurl", lab);
        lab = new JLabel(getIcon("arxiv"));
        lab.setToolTipText(Globals.lang("Open") + " ArXiv URL");
        tableIcons.put("eprint", lab);
        lab = new JLabel(getIcon("doiSmall"));
        lab.setToolTipText(Globals.lang("Open") + " DOI " + Globals.lang("web link"));
        tableIcons.put("doi", lab);
        lab = new JLabel(getIcon("psSmall"));
        lab.setToolTipText(Globals.lang("Open") + " PS");
        tableIcons.put("ps", lab);
        lab = new JLabel(getIcon("psSmall"));
        lab.setToolTipText(Globals.lang("Open folder"));
        tableIcons.put(GUIGlobals.FOLDER_FIELD, lab);
        lab = new JLabel(getIcon("psSmall"));
        lab.setToolTipText(Globals.lang("Open file"));
        tableIcons.put(GUIGlobals.FILE_FIELD, lab);

        for (ExternalFileType fileType : Globals.prefs.getExternalFileTypeSelection()) {
            lab = new JLabel(fileType.getIcon());
            lab.setToolTipText(Globals.lang("Open " + fileType.getName() + " file"));
            tableIcons.put(fileType.getName(), lab);
        }

        lab = new JLabel(Relevance.getInstance().getRepresentingIcon());
        lab.setToolTipText(Relevance.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_RELEVANCE, lab);

        lab = new JLabel(Quality.getInstance().getRepresentingIcon());
        lab.setToolTipText(Quality.getInstance().getToolTip());
        //tableIcons.put("quality", lab);
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_QUALITY, lab);

        // Ranking item in the menu uses one star
        lab = new JLabel(Rank.getInstance().getRepresentingIcon());
        lab.setToolTipText(Rank.getInstance().getToolTip());
//        lab.setName("0");
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_RANKING, lab);

        // Priority icon used for the menu
        lab = new JLabel(Priority.getInstance().getRepresentingIcon());
        lab.setToolTipText(Rank.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_PRIORITY, lab);

        // Read icon used for menu
        lab = new JLabel(ReadStatus.getInstance().getRepresentingIcon());
        lab.setToolTipText(ReadStatus.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_READ, lab);

        // Print icon used for menu
        lab = new JLabel(Printed.getInstance().getRepresentingIcon());
        lab.setToolTipText(Printed.getInstance().getToolTip());
        tableIcons.put(SpecialFieldsUtils.FIELDNAME_PRINTED, lab);

        //jabRefFont = new Font("arial", Font.ITALIC/*+Font.BOLD*/, 20); 
        if (Globals.prefs.getBoolean(JabRefPreferences.EDITOR_EMACS_KEYBINDINGS)) {
            EmacsKeyBindings.load();
        }
    }

}
