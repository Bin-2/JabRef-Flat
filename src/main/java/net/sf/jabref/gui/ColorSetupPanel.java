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

import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.builder.DefaultFormBuilder;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import net.sf.jabref.GUIGlobals;

import net.sf.jabref.Globals;
import net.sf.jabref.JabRefPreferences;

/**
 * Created by IntelliJ IDEA. User: alver Date: Oct 10, 2005 Time: 4:29:35 PM To
 * change this template use File | Settings | File Templates.
 */
public class ColorSetupPanel extends JPanel {

    private final static int ICON_WIDTH = 30, ICON_HEIGHT = 20;

    private final ArrayList<ColorButton> buttons = new ArrayList<>();

    public ColorSetupPanel() {

        FormLayout layout = new FormLayout("30dlu, 4dlu, fill:pref, 4dlu, fill:pref, 8dlu, 30dlu, 4dlu, fill:pref, 4dlu, fill:pref", "");
        DefaultFormBuilder builder = new DefaultFormBuilder(layout);

        /*
        buttons.add(new ColorButton("tableText", Globals.lang("Table text color")));
        buttons.add(new ColorButton("tableBackground", Globals.lang("Table background color")));
        buttons.add(new ColorButton("tableReqFieldBackground", Globals.lang("Background color for required fields")));
        buttons.add(new ColorButton("tableOptFieldBackground", Globals.lang("Background color for optional fields")));
        buttons.add(new ColorButton("incompleteEntryBackground", Globals.lang("Color for marking incomplete entries")));
        buttons.add(new ColorButton("gridColor", Globals.lang("Table grid color")));
        buttons.add(new ColorButton("fieldEditorTextColor", Globals.lang("Entry editor font color")));
        buttons.add(new ColorButton("activeFieldEditorBackgroundColor", Globals.lang("Entry editor active background color")));

        buttons.add(new ColorButton("markedEntryBackground0", Globals.lang("Marking color %0", "1")));
        buttons.add(new ColorButton("markedEntryBackground1", Globals.lang("Marking color %0", "2")));
        buttons.add(new ColorButton("markedEntryBackground2", Globals.lang("Marking color %0", "3")));
        buttons.add(new ColorButton("markedEntryBackground3", Globals.lang("Marking color %0", "4")));
        buttons.add(new ColorButton("markedEntryBackground4", Globals.lang("Marking color %0", "5")));
        buttons.add(new ColorButton("markedEntryBackground5", Globals.lang("Import marking color")));
        buttons.add(new ColorButton("validFieldBackgroundColor", Globals.lang("Entry editor background color")));
        buttons.add(new ColorButton("invalidFieldBackgroundColor", Globals.lang("Entry editor invalid field color")));
        buttons.add(new ColorButton("tableText", Globals.lang("Table text color"), "table"));
        buttons.add(new ColorButton("markedEntryBackground0", Globals.lang("Marking color %0", "1"), "mark"));
         */
        buttons.add(new ColorButton("tableBackground", Globals.lang("Table background color"), "table"));
        buttons.add(new ColorButton("markedEntryBackground1", Globals.lang("Marking color %0", "2"), "mark"));

        buttons.add(new ColorButton("tableReqFieldBackground", Globals.lang("Background color for required fields"), "table"));
        buttons.add(new ColorButton("markedEntryBackground2", Globals.lang("Marking color %0", "3"), "mark"));

        buttons.add(new ColorButton("tableOptFieldBackground", Globals.lang("Background color for optional fields"), "table"));
        buttons.add(new ColorButton("markedEntryBackground3", Globals.lang("Marking color %0", "4"), "mark"));

        buttons.add(new ColorButton("incompleteEntryBackground", Globals.lang("Color for marking incomplete entries"), "table"));
        buttons.add(new ColorButton("markedEntryBackground4", Globals.lang("Marking color %0", "5"), "mark"));

        buttons.add(new ColorButton("gridColor", Globals.lang("Table grid color"), "table"));
        buttons.add(new ColorButton("markedEntryBackground5", Globals.lang("Import marking color"), "mark"));

        buttons.add(new ColorButton("fieldEditorTextColor", Globals.lang("Entry editor font color"), "table"));
        buttons.add(new ColorButton("validFieldBackgroundColor", Globals.lang("Entry editor background color"), "mark"));

        buttons.add(new ColorButton("activeFieldEditorBackgroundColor", Globals.lang("Entry editor active background color"), "table"));
        buttons.add(new ColorButton("invalidFieldBackgroundColor", Globals.lang("Entry editor invalid field color"), "mark"));

        for (ColorButton but : buttons) {
            builder.append(but);
            builder.append(but.getDefaultButton());
            builder.append(but.getName());
            but.addActionListener(new ColorButtonListener(but));
        }

        updateUIForThemeChange();

        // Add info text (only show if in dark mode, or always show as reminder)
        builder.nextLine();
        builder.append(new JLabel(""), 11); // Empty row for spacing

        builder.nextLine();
        JLabel infoLabel = new JLabel("<html><i><strike>Table color customization is only available in Light theme mode.</strike></i><br><i>Table color customization is set to default theme.</i></html>");
        infoLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.ITALIC, infoLabel.getFont().getSize() - 1f));

        // Center the label across all columns
        builder.append(infoLabel, 11); // Span across all 11 columns

        setLayout(new BorderLayout());
        add(builder.getPanel(), BorderLayout.CENTER);

        setValues();
    }

    public void setValues() {
        for (ColorButton but : buttons) {
            but.setColor(Globals.prefs.getColor(but.getKey()));
        }

    }

    public void storeSettings() {
        for (ColorButton but : buttons) {
            Globals.prefs.putColor(but.getKey(), but.getColor());
        }
    }

    class ColorButtonListener implements ActionListener {

        private final ColorButton button;

        public ColorButtonListener(ColorButton button) {
            this.button = button;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Color chosen = JColorChooser.showDialog(null, button.getName(), button.getColor());
            if (chosen != null) {
                button.setColor(chosen);
            }
        }
    }

    public void updateUIForThemeChange() {
        // Update all color buttons when theme changes
        for (ColorButton but : buttons) {
            but.applyButtonState();
            // but.updateDefaultButtonState();
            but.disableAdjacentTableButton();
        }

        // Update the info label text based on current theme
//        updateInfoLabel();
        // Refresh the UI
        revalidate();
        repaint();
    }

    private void updateInfoLabel() {
        // Find and update the info label if it exists
        Component[] components = getComponents();
        for (Component comp : components) {
            if (comp instanceof JLabel) {
                JLabel label = (JLabel) comp;
                if (label.getText() != null && label.getText().contains("Table color customization")) {
                    String theme = JabRefPreferences.getInstance().get("Theme");
                    String theme_mode = theme.toLowerCase().contains("dark")
                            || theme.toLowerCase().contains("carbon") ? "dark" : "light";

                    boolean isLightTheme = "Light".equalsIgnoreCase(theme_mode);

                    String newText = isLightTheme
                            ? "<html><i>Table color settings are currently enabled (Light mode).</i></html>"
                            : "<html><i>Table color customization is only available in Light theme mode.</i></html>";

                    label.setText(newText);
                    break;
                }
            }
        }
    }

    /**
     * A button to display the chosen color, and hold key information about a
     * color setting. Includes a method to produce a Default button for this
     * setting.
     */
    class ColorButton extends JButton implements Icon {

        private Color color = Color.white;
        private final String key;
        private final String name;
        private String tableOrMark = null;

        public ColorButton(String key, String name) {
            setIcon(this);
            this.key = key;
            this.name = name;
            this.tableOrMark = tableOrMark;
            setBorder(BorderFactory.createRaisedBevelBorder());
        }

        public ColorButton(String key, String name, String tableOrMark) {
            setIcon(this);
            this.key = key;
            this.name = name;
            this.tableOrMark = tableOrMark;
            setBorder(BorderFactory.createRaisedBevelBorder());

            // applyButtonState();
            if ("table".equalsIgnoreCase(tableOrMark)) {
                this.setEnabled(false);
                this.setToolTipText(null);
            }
        }

        public void applyButtonState() {
            if ("table".equalsIgnoreCase(tableOrMark)) {
                String theme = JabRefPreferences.getInstance().get("Theme");
                String theme_mode = theme.toLowerCase().contains("dark")
                        || theme.toLowerCase().contains("carbon") ? "dark" : "light";

                boolean shouldEnable = "Light".equalsIgnoreCase(theme_mode);
                // System.out.println(shouldEnable);
                setEnabled(shouldEnable);
                if (!shouldEnable) {
                    setToolTipText("Table color customization is only available in Light theme");
                } else {
                    setToolTipText(null);
                }
            }
        }

        private void applyDefaultButtonState(JButton defaultButton) {
            if ("table".equalsIgnoreCase(tableOrMark)) {
                String theme = JabRefPreferences.getInstance().get("Theme");
                String theme_mode = theme.toLowerCase().contains("dark")
                        || theme.toLowerCase().contains("carbon") ? "dark" : "light";

                boolean shouldEnable = "Light".equalsIgnoreCase(theme_mode);
                defaultButton.setEnabled(shouldEnable);
                if (!shouldEnable) {
                    defaultButton.setToolTipText("Table color customization is only available in Light theme");
                } else {
                    defaultButton.setToolTipText(null);
                }
            }
        }

        public void updateDefaultButtonState() {
            // Find the default button for this color button and update its state
            Container parent = getParent();
            if (parent != null) {
                Component[] components = parent.getComponents();
                for (int i = 0; i < components.length; i++) {
                    if (components[i] == this) {
                        // The default button is usually the next component
                        if (i + 1 < components.length && components[i + 1] instanceof JButton) {
                            JButton defaultButton = (JButton) components[i + 1];
                            applyDefaultButtonState(defaultButton);
                        }
                        break;
                    }
                }
            }
        }

        private void applyDisableTableButtonState(JButton defaultButton) {
            if ("table".equalsIgnoreCase(tableOrMark)) {
                defaultButton.setEnabled(false);
                defaultButton.setToolTipText(null);
            }
        }

        public void disableAdjacentTableButton() {
            Container parent = getParent();
            if (parent != null) {
                Component[] components = parent.getComponents();
                for (int i = 0; i < components.length; i++) {
                    if (components[i] == this) {
                        if (i + 1 < components.length && components[i + 1] instanceof JButton) {
                            JButton defaultButton = (JButton) components[i + 1];
                            applyDisableTableButtonState(defaultButton);
                        }
                        break;
                    }
                }
            }
        }

        public JButton getDefaultButton() {
            JButton toDefault = new JButton(Globals.lang("Default"));

            // Apply initial state
            applyDefaultButtonState(toDefault);

            toDefault.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    setColor(Globals.prefs.getDefaultColor(key));
                    repaint();
                }
            });
            return toDefault;
        }

        public String getKey() {
            return key;
        }

        @Override
        public String getName() {
            return name;
        }

        public Color getColor() {
            return color;
        }

        public void setColor(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Rectangle r = g.getClipBounds();
            g.setColor(color);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override
        public int getIconWidth() {
            return ICON_WIDTH;
        }

        @Override
        public int getIconHeight() {
            return ICON_HEIGHT;
        }

    }

}
