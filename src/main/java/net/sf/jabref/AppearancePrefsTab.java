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
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.*;

import net.sf.jabref.gui.ColorSetupPanel;

import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.layout.FormLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Window;
import java.util.Enumeration;
import javax.swing.plaf.FontUIResource;
import net.sf.jabref.gui.MainTable;

class AppearancePrefsTab extends JPanel implements PrefsTab {

    JabRefPreferences _prefs;
    private final JCheckBox colorCodes;
    private final JCheckBox overrideFonts;//, useCustomIconTheme;
    private final JCheckBox showGrid;//, useCustomIconTheme;
    private final ColorSetupPanel colorPanel = new ColorSetupPanel();
    private Font font = GUIGlobals.CURRENTFONT;
    private int oldMenuFontSize;
    private boolean oldOverrideFontSize;
    private final JTextField fontSize;//, customIconThemeFile;
    private final JTextField rowPadding;//, customIconThemeFile;

    private final JCheckBox useThemeSemanticColors;
    private final JComboBox<String> themeCombo;
    private boolean updatingThemeCombo;
    private String themeBeforePreview;
    private String pendingTheme;
    private boolean themePreviewChanged;

    /**
     * Customization of appearance parameters.
     *
     * @param prefs a <code>JabRefPreferences</code> value
     */
    public AppearancePrefsTab(JabRefFrame frame, JabRefPreferences prefs) {
        _prefs = prefs;

        setLayout(new BorderLayout());

        // Font sizes:
        fontSize = new JTextField(5);

        // Row padding size:
        rowPadding = new JTextField(5);

        colorCodes = new JCheckBox(
                Globals.lang("Color codes for required and optional fields"));
        /*antialias = new JCheckBox(Globals.lang
                  ("Use antialiasing font"));*/
        overrideFonts = new JCheckBox(Globals.lang("Override default font settings"));

        showGrid = new JCheckBox(Globals.lang("Show gridlines"));

        useThemeSemanticColors = new JCheckBox(
                Globals.lang("Use theme-aware semantic colors"));

        FormLayout layout = new FormLayout("1dlu, 8dlu, left:pref, 4dlu, fill:pref, 4dlu, fill:60dlu, 4dlu, fill:pref",
                "");
        DefaultFormBuilder builder = new DefaultFormBuilder(layout);
        builder.leadingColumnOffset(2);
        JLabel lab;
        builder.appendSeparator(Globals.lang("General"));
        JPanel p1 = new JPanel();
        lab = new JLabel(Globals.lang("Menu and label font size") + ":");
        p1.add(lab);
        p1.add(fontSize);
        builder.append(p1);
        builder.nextLine();
        builder.append(overrideFonts);
        builder.nextLine();
        builder.appendSeparator(Globals.lang("Table appearance"));
        //builder.append(antialias);
        //builder.nextLine();
        JPanel p2 = new JPanel();
        p2.add(new JLabel(Globals.lang("Table row height padding") + ":"));
        p2.add(rowPadding);
        builder.append(p2);
        builder.nextLine();
        builder.append(colorCodes);
        builder.nextLine();
        builder.append(showGrid);
        builder.nextLine();
        JButton fontButton = new JButton(Globals.lang("Set table font"));
        builder.append(fontButton);
        builder.nextLine();
        builder.appendSeparator(Globals.lang("Table and entry editor colors"));
        builder.append(colorPanel);
        builder.nextLine();
        builder.appendSeparator(Globals.lang("Theme"));

        // Create theme selection combo box
        String[] themes = ThemeManager.getAvailableThemes();

        // Default to FlatLight
        String currentTheme = Globals.prefs.get(
                "Theme", ThemeManager.DEFAULT_THEME);
        pendingTheme = currentTheme;

        themeCombo = new JComboBox<>(themes);
        themeCombo.setSelectedItem(currentTheme);
        builder.append(themeCombo);
        builder.nextLine();

        builder.append(useThemeSemanticColors);
        builder.nextLine();

        useThemeSemanticColors.addActionListener(e -> {
            boolean enabled = useThemeSemanticColors.isSelected();

            colorPanel.setThemeSemanticColorsEnabled(enabled);
            ThemeColorPalette.setSemanticColorsPreview(enabled);

            ThemeWatcher.notifyThemeChanged();
        });

        // Apply the selected theme as a preview. It is persisted only on OK.
        themeCombo.addActionListener(e -> {
            if (updatingThemeCombo) {
                return;
            }

            String selectedTheme = (String) themeCombo.getSelectedItem();
            if (applyTheme(frame, selectedTheme)) {
                pendingTheme = selectedTheme;
                themePreviewChanged = true;
            }
        });

        JPanel upper = new JPanel(),
                sort = new JPanel(),
                namesp = new JPanel(),
                iconCol = new JPanel();
        GridBagLayout gbl = new GridBagLayout();
        upper.setLayout(gbl);
        sort.setLayout(gbl);
        namesp.setLayout(gbl);
        iconCol.setLayout(gbl);

        overrideFonts.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                fontSize.setEnabled(overrideFonts.isSelected());
            }
        });

        fontButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Font f = new FontSelectorDialog(null, GUIGlobals.CURRENTFONT).getSelectedFont();
                if (f != null) {
                    font = f;
                }
            }
        });

        JPanel pan = builder.getPanel();
        pan.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        add(pan, BorderLayout.CENTER);
    }

    private boolean applyTheme(JabRefFrame frame, String themeName) {
        String theme = ThemeManager.normalizeThemeName(themeName);

        try {
            // Installing the Look & Feel is the success boundary for a theme
            // preview. Once this succeeds, preview/cancel state must reflect
            // that the active theme has changed even if a later UI refresh
            // encounters a problem.
            ThemeManager.applyTheme(theme);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to apply theme: " + ex.getMessage(),
                    "Theme Error",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }

        try {
            // The Look & Feel is now authoritative, so load the matching icons.
            GUIGlobals.setUpIconTheme();

            // Refresh shared theme resources and notify theme-aware components.
            ThemeWatcher.notifyThemeChanged();

            // Refresh the application UI.
            frame.updateUIForThemeChange();
            colorPanel.updateUIForThemeChange();
        } catch (RuntimeException ex) {
            ex.printStackTrace();
            Globals.logger("Theme was applied, but the UI refresh failed: "
                    + ex.getMessage());
            JOptionPane.showMessageDialog(
                    this,
                    "The theme was applied, but some UI components could not "
                    + "be refreshed. Restart JabRef if the interface looks inconsistent.",
                    "Theme Refresh Error",
                    JOptionPane.WARNING_MESSAGE);
        }

        return true;
    }

    void beginThemePreviewSession() {
        LookAndFeel currentLookAndFeel = UIManager.getLookAndFeel();
        themeBeforePreview = currentLookAndFeel == null
                ? null
                : currentLookAndFeel.getClass().getName();

        pendingTheme = _prefs.get("Theme", ThemeManager.DEFAULT_THEME);
        themePreviewChanged = false;
    }

    boolean cancelThemePreview(JabRefFrame frame) {
        if (!themePreviewChanged || themeBeforePreview == null) {
            return false;
        }

        if (applyTheme(frame, themeBeforePreview)) {
            themePreviewChanged = false;
            return true;
        }
        return false;
    }

    boolean applyImportedTheme(JabRefFrame frame) {
        String importedTheme = ThemeManager.normalizeThemeName(
                _prefs.get("Theme", ThemeManager.DEFAULT_THEME));

        if (!applyTheme(frame, importedTheme)) {
            return false;
        }

        // Importing preferences writes directly to the backing store, so the
        // imported theme becomes the baseline for any later preview/cancel.
        beginThemePreviewSession();
        return true;
    }

    @Override
    public void setValues() {

        ThemeColorPalette.clearSemanticColorsPreview();

        String storedTheme = ThemeManager.normalizeThemeName(
                _prefs.get("Theme", ThemeManager.DEFAULT_THEME));
        pendingTheme = storedTheme;
        updatingThemeCombo = true;
        try {
            themeCombo.setSelectedItem(storedTheme);
        } finally {
            updatingThemeCombo = false;
        }

        colorCodes.setSelected(_prefs.getBoolean("tableColorCodesOn"));
        //antialias.setSelected(_prefs.getBoolean("antialias"));
        fontSize.setText("" + _prefs.getInt("menuFontSize"));
        rowPadding.setText("" + _prefs.getInt("tableRowPadding"));
        oldMenuFontSize = _prefs.getInt("menuFontSize");
        overrideFonts.setSelected(_prefs.getBoolean("overrideDefaultFonts"));
        oldOverrideFontSize = overrideFonts.isSelected();
        fontSize.setEnabled(overrideFonts.isSelected());
        showGrid.setSelected(_prefs.getBoolean("tableShowGrid"));

        useThemeSemanticColors.setSelected(
                _prefs.getBoolean(JabRefPreferences.USE_THEME_SEMANTIC_COLORS));

        colorPanel.setThemeSemanticColorsEnabled(
                useThemeSemanticColors.isSelected());

        colorPanel.setValues();
    }

    /**
     * Store changes to table preferences. This method is called when the user
     * clicks Ok.
     *
     */
    public void storeSettings() {

        _prefs.put("Theme", ThemeManager.normalizeThemeName(pendingTheme));
        themePreviewChanged = false;

        _prefs.putBoolean("tableColorCodesOn", colorCodes.isSelected());
        //_prefs.putBoolean("antialias", antialias.isSelected());
        _prefs.put("fontFamily", font.getFamily());
        _prefs.putInt("fontStyle", font.getStyle());
        _prefs.putInt("fontSize", font.getSize());
        _prefs.putBoolean("overrideDefaultFonts", overrideFonts.isSelected());
        GUIGlobals.CURRENTFONT = font;
        colorPanel.storeSettings();
        _prefs.putBoolean("tableShowGrid", showGrid.isSelected());

        _prefs.putBoolean(
                JabRefPreferences.USE_THEME_SEMANTIC_COLORS,
                useThemeSemanticColors.isSelected());
        ThemeColorPalette.clearSemanticColorsPreview();

        try {
            int size = Integer.parseInt(fontSize.getText());
            if ((overrideFonts.isSelected() != oldOverrideFontSize)
                    || (size != oldMenuFontSize)) {
                _prefs.putInt("menuFontSize", size);
                JOptionPane.showMessageDialog(null,
                        Globals.lang("You have changed the menu and label font size.")
                                .concat(" ")
                                .concat(Globals.lang("You must restart JabRef for this to come into effect.")),
                        Globals.lang("Changed font settings"),
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (NumberFormatException ex) {
            ex.printStackTrace();
        }
        try {
            int padding = Integer.parseInt(rowPadding.getText());
            _prefs.putInt("tableRowPadding", padding);
        } catch (NumberFormatException ex) {
            ex.printStackTrace();
        }
    }

    private boolean validateIntegerField(String fieldName, String fieldValue, String errorTitle) {
        try {
            // Test if the field value is a number:
            Integer.parseInt(fieldValue);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(null, Globals.lang("You must enter an integer value in the text field for") + " '"
                    + Globals.lang(fieldName) + "'", Globals.lang(errorTitle),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    public boolean readyToClose() {
        // Test if font size is a number:
        return validateIntegerField("Menu and label font size", fontSize.getText(), "Changed font settings") != false;
    }

    public String getTabName() {
        return Globals.lang("Appearance");
    }

    public void cancelPreview() {
        ThemeColorPalette.clearSemanticColorsPreview();
    }

}
