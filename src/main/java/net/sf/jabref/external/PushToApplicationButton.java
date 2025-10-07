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
package net.sf.jabref.external;

import com.jgoodies.forms.builder.ButtonBarBuilder;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.JabRefFrame;
import net.sf.jabref.MnemonicAwareAction;
import net.sf.jabref.oo.OpenOfficePanel;
import net.sf.jabref.plugin.PluginCore;
import net.sf.jabref.plugin.core.JabRefPlugin;
import net.sf.jabref.plugin.core.generated._JabRefPlugin;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import javax.swing.border.Border;

/**
 * Customized UI component for pushing to external applications. Has a selection
 * popup menu to change the selected external application. This class implements
 * the ActionListener interface. When actionPerformed() is invoked, the
 * currently selected PushToApplication is activated. The actionPerformed()
 * method can be called with a null argument.
 */
public class PushToApplicationButton implements ActionListener {

    public static List<PushToApplication> applications;

    private JabRefFrame frame;
    public List<PushToApplication> pushActions;
    private JPanel comp;
    private JButton pushButton, menuButton;
    public int selected = 0;
    private JPopupMenu popup = null;
    private HashMap<PushToApplication, PushToApplicationAction> actions = new HashMap<PushToApplication, PushToApplicationAction>();

    private int buttonDimShift = 0;
    private final Dimension buttonDim = new Dimension(GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE - buttonDimShift, GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE - buttonDimShift);
    // private static final URL ARROW_ICON = GUIGlobals.class.getResource("/images/secondary_sorted_reverse.png");
    private MenuAction mAction = new MenuAction();
    private JPopupMenu optPopup = new JPopupMenu();
    private JMenuItem settings = new JMenuItem(Globals.lang("Settings"));

    /**
     * Set up the current available choices:
     */
    static {

        applications = new ArrayList<PushToApplication>();

        JabRefPlugin jabrefPlugin = JabRefPlugin.getInstance(PluginCore.getManager());
        if (jabrefPlugin != null) {
            List<_JabRefPlugin.PushToApplicationExtension> plugins = jabrefPlugin.getPushToApplicationExtensions();
            for (_JabRefPlugin.PushToApplicationExtension extension : plugins) {
                applications.add(extension.getPushToApp());
            }

            applications.add(new PushToLatexEditor());
            applications.add(new PushToTeXstudio());
            applications.add(new PushToLyx());
            applications.add(new PushToEmacs());
            applications.add(new PushToWinEdt());
            applications.add(new PushToVim());
            applications.add(OpenOfficePanel.getInstance());

            // Finally, sort the entries:
            //Collections.sort(applications, new PushToApplicationComparator());
        }
    }

    public PushToApplicationButton(JabRefFrame frame, List<PushToApplication> pushActions) {
        this.frame = frame;
        this.pushActions = pushActions;
        init();
    }

    // Add SVG arrow icon:
    private Icon getArrowIcon() {
        Icon svgArrow = GUIGlobals.getIcon("scrollbar", 16, 16);
        if (svgArrow != null) {
            return svgArrow;
        }
        // Fallback to the original PNG if SVG not found
        URL pngArrow = GUIGlobals.class.getResource("/images/secondary_sorted_reverse.png");
        return pngArrow != null ? new ImageIcon(pngArrow) : null;
    }

    private void init() {
        comp = new JPanel();
        comp.setLayout(new BorderLayout());

        // Create an empty border that will show on hover
        final Border emptyBorder = BorderFactory.createEmptyBorder(1, 1, 1, 1);
        final Border lineBorder = BorderFactory.createLineBorder(Color.GRAY, 1);
        final Border compoundBorder = BorderFactory.createCompoundBorder(lineBorder, emptyBorder);

        comp.setBorder(emptyBorder); // Start with empty border

        Icon arrowIcon = getArrowIcon();
        menuButton = new JButton(arrowIcon);
        menuButton.setMargin(new Insets(0, 0, 0, 0));

        // Create a compound border with left margin
        int leftMargin = 6;
        Border marginBorder = BorderFactory.createEmptyBorder(0, leftMargin, 0, 0);
        Border existingBorder = menuButton.getBorder();

        menuButton.setOpaque(false);
        // Set proper size based on toolbar icon size
        int arrowWidth = GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE / 3;
        if (arrowIcon != null) {
            menuButton.setPreferredSize(new Dimension(
                    arrowWidth + leftMargin, // Smaller for dropdown arrow
                    GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE
            ));
        }
        if (existingBorder != null) {
            menuButton.setBorder(BorderFactory.createCompoundBorder(marginBorder, existingBorder));
        } else {
            menuButton.setBorder(marginBorder);
        }

        // menuButton.setOpaque(false); // Transparent
        // menuButton.setContentAreaFilled(false); // No fill
        // menuButton.setBorderPainted(false); // No border
        menuButton.addActionListener(new MenuButtonActionListener());
        menuButton.setToolTipText(Globals.lang("Select external application"));
        pushButton = new JButton();
        // Main button uses full toolbar size
        pushButton.setPreferredSize(new Dimension(
                GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE,
                GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE
        ));

        if (Globals.prefs.hasKey("pushToApplication")) {
            String appSelected = Globals.prefs.get("pushToApplication");
            for (int i = 0; i < pushActions.size(); i++) {
                PushToApplication toApp = pushActions.get(i);
                if (toApp.getName().equals(appSelected)) {
                    selected = i;
                    break;
                }
            }
        }

        setSelected(selected);
        pushButton.addActionListener(this);
        pushButton.addMouseListener(new PushButtonMouseListener());
//        pushButton.setOpaque(false);
//        menuButton.setOpaque(false);

//        comp.setOpaque(false);
        comp.add(pushButton, BorderLayout.WEST);
        comp.add(menuButton, BorderLayout.EAST);
        // comp.setBorder(BorderFactory.createLineBorder(Color.gray));
        // comp.setBorder(compoundBorder);

        // Adjust total width to account for padding
        int totalWidth = GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE + leftMargin + arrowWidth;
        comp.setPreferredSize(new Dimension(totalWidth, GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE));
        comp.setMaximumSize(comp.getPreferredSize());

        optPopup.add(settings);
        settings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                PushToApplication toApp = pushActions.get(selected);
                JPanel options = toApp.getSettingsPanel();
                if (options != null) {
                    showSettingsDialog(frame, toApp, options);
                }

            }
        });

        buildPopupMenu();
    }

    /**
     * Create a selection menu for the available "Push" options.
     */
    private void buildPopupMenu() {
        popup = new JPopupMenu();
        int j = 0;
        for (PushToApplication application : pushActions) {
            JMenuItem item = new JMenuItem(application.getApplicationName(),
                    application.getIcon());
            item.setToolTipText(application.getTooltip());
            item.addActionListener(new PopupItemActionListener(j));
            popup.add(item);
            j++;
        }
    }

    /**
     * Update the PushButton to default to the given application.
     *
     * @param i The List index of the application to default to.
     */
    public void setSelected(int i) {
        this.selected = i;
        PushToApplication toApp = pushActions.get(i);
        // Get the application's icon and scale it to toolbar size using SVG-aware scaling
        Icon appIcon = toApp.getIcon();
        if (appIcon != null) {
            // Use GUIGlobals to get a properly scaled version for the toolbar
            Icon toolbarIcon = GUIGlobals.getToolbarIconOnly(getIconNameForApplication(toApp));
            // System.out.println(toolbarIcon);
            if (toolbarIcon != null) {
                appIcon = toolbarIcon;
            } else {
                // Fallback: if no specific toolbar icon, try to get a scaled version
                appIcon = getScaledIcon(appIcon, GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE, GUIGlobals.CURRENT_TOOLBAR_ICON_SIZE);
            }
        }
        // System.out.println(appIcon);

        pushButton.setOpaque(false);
        pushButton.setIcon(appIcon);
        pushButton.setToolTipText(toApp.getTooltip());
        pushButton.setPreferredSize(buttonDim);

        Globals.prefs.put("pushToApplication", toApp.getName());
        mAction.setTitle(toApp.getApplicationName());
    }

    /**
     * Get an appropriate icon name for the application
     */
    private String getIconNameForApplication(PushToApplication app) {
        // Map application types to icon names
        String name = app.getName().toLowerCase();
        // System.out.println(name);
        if (name.contains("lyx")) {
            return "lyx";
        }
        if (name.contains("emacs")) {
            return "emacs";
        }
        if (name.contains("vim")) {
            return "vim";
        }
        if (name.contains("texstudio")) {
            return "texstudio";
        }
        if (name.contains("latex")) {
            return "latex";
        }
        if (name.contains("openoffice") || name.contains("libreoffice")) {
            return "openoffice";
        }
        return "externalApp"; // default
    }

    /**
     * Safely scale an icon without converting to image (preserves SVG quality)
     */
    private Icon getScaledIcon(Icon originalIcon, int width, int height) {
        if (originalIcon == null) {
            return null;
        }

        // If it's already the right size, return as-is
        if (originalIcon.getIconWidth() == width && originalIcon.getIconHeight() == height) {
            return originalIcon;
        }

        // For SVG icons, let GUIGlobals handle the scaling
        // For non-SVG icons, we'll need to handle them differently
        if (originalIcon instanceof ImageIcon) {
            // It's a raster icon - scale it (but try to avoid this if possible)
            Image image = ((ImageIcon) originalIcon).getImage();
            Image scaledImage = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        }

        // For other icon types (including SVG), return original and let the layout handle sizing
        return originalIcon;
    }

    /**
     * Get the toolbar component for the push button.
     *
     * @return The component.
     */
    public Component getComponent() {
        return comp;
    }

    public Action getMenuAction() {
        return mAction;
    }

    public void actionPerformed(ActionEvent e) {
        PushToApplication toApp = pushActions.get(selected);

        // Lazy initialization of the push action:
        PushToApplicationAction action = actions.get(toApp);
        if (action == null) {
            action = new PushToApplicationAction(frame, toApp);
            actions.put(toApp, action);
        }
        action.actionPerformed(new ActionEvent(toApp, 0, "push"));
    }

    static class BooleanHolder {

        public BooleanHolder(boolean value) {
            this.value = value;
        }
        public boolean value;
    }

    public static void showSettingsDialog(Object parent, PushToApplication toApp, JPanel options) {

        final BooleanHolder okPressed = new BooleanHolder(false);
        JDialog dg;
        if (parent instanceof JDialog) {
            dg = new JDialog((JDialog) parent, Globals.lang("Settings"), true);
        } else {
            dg = new JDialog((JFrame) parent, Globals.lang("Settings"), true);
        }
        final JDialog diag = dg;
        options.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        diag.getContentPane().add(options, BorderLayout.CENTER);
        ButtonBarBuilder bb = new ButtonBarBuilder();
        JButton ok = new JButton(Globals.lang("Ok"));
        JButton cancel = new JButton(Globals.lang("Cancel"));
        bb.addGlue();
        bb.addButton(ok);
        bb.addButton(cancel);
        bb.addGlue();
        bb.getPanel().setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        diag.getContentPane().add(bb.getPanel(), BorderLayout.SOUTH);
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                okPressed.value = true;
                diag.dispose();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                diag.dispose();
            }
        });
        // Key bindings:
        ActionMap am = bb.getPanel().getActionMap();
        InputMap im = bb.getPanel().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(Globals.prefs.getKey("Close dialog"), "close");
        am.put("close", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                diag.dispose();
            }
        });
        diag.pack();
        if (parent instanceof JDialog) {
            diag.setLocationRelativeTo((JDialog) parent);
        } else {
            diag.setLocationRelativeTo((JFrame) parent);
        }
        // Show the dialog:
        diag.setVisible(true);
        // If the user pressed Ok, ask the PushToApplication implementation
        // to store its settings:
        if (okPressed.value) {
            toApp.storeSettings();
        }
    }

    class PopupItemActionListener implements ActionListener {

        private int index;

        public PopupItemActionListener(int index) {
            this.index = index;
        }

        public void actionPerformed(ActionEvent e) {
            // Change the selection:
            setSelected(index);
            // Invoke the selected operation (is that expected behaviour?):
            //PushToApplicationButton.this.actionPerformed(null);
            // It makes sense to transfer focus to the push button after the
            // menu closes:
            pushButton.requestFocus();
        }
    }

    class MenuButtonActionListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            // Lazy initialization of the popup menu:
            if (popup == null) {
                buildPopupMenu();
            }
            popup.show(comp, 0, menuButton.getHeight());
        }
    }

    class MenuAction extends MnemonicAwareAction {

        public MenuAction() {
            putValue(ACCELERATOR_KEY, Globals.prefs.getKey("Push to application"));
        }

        public void setTitle(String appName) {
            putValue(NAME, Globals.lang("Push entries to external application (%0)",
                    appName));
        }

        public void actionPerformed(ActionEvent e) {
            PushToApplicationButton.this.actionPerformed(null);
        }
    }

    class PushButtonMouseListener extends MouseAdapter {

        public void mousePressed(MouseEvent event) {
            if (event.isPopupTrigger()) {
                processPopupTrigger(event);
            }
        }

        public void mouseClicked(MouseEvent event) {
            if (event.isPopupTrigger()) {
                processPopupTrigger(event);
            }
        }

        public void mouseReleased(MouseEvent event) {
            if (event.isPopupTrigger()) {
                processPopupTrigger(event);
            }
        }

        private void processPopupTrigger(MouseEvent e) {
            // We only want to show the popup if a settings panel exists for the selected
            // item:
            PushToApplication toApp = pushActions.get(selected);
            if (toApp.getSettingsPanel() != null) {
                optPopup.show(pushButton, e.getX(), e.getY());
            }

        }
    }

    /**
     * Comparator for sorting the selection according to name.
     */
    static class PushToApplicationComparator implements Comparator<PushToApplication> {

        public int compare(PushToApplication one, PushToApplication two) {
            return one.getName().compareTo(two.getName());
        }
    }
}
