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

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URL;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JToolBar;

import com.jgoodies.uif_lite.panel.SimpleInternalFrame;
import javax.swing.Icon;
import javax.swing.SwingUtilities;

public abstract class SidePaneComponent extends SimpleInternalFrame {

    protected JButton close = new JButton(GUIGlobals.getImage("close"));
    protected JButton up = new JButton(GUIGlobals.getImage("up"));
    protected JButton down = new JButton(GUIGlobals.getImage("down"));

    protected boolean visible = false;

    protected SidePaneManager manager;

    protected BasePanel panel = null;

    public SidePaneComponent(SidePaneManager manager, URL icon, String title) {
        super(new ImageIcon(icon), title);
        this.manager = manager;
        setSelected(true);
        JToolBar tlb = new JToolBar();
        close.setMargin(new Insets(0, 0, 0, 0));
        // tlb.setOpaque(false);
        close.setBorder(null);
        up.setMargin(new Insets(0, 0, 0, 0));
        down.setMargin(new Insets(0, 0, 0, 0));
        up.setBorder(null);
        down.setBorder(null);
        up.addActionListener(new UpButtonListener());
        down.addActionListener(new DownButtonListener());
        tlb.setFloatable(false);
        tlb.add(up);
        tlb.add(down);
        tlb.add(close);
        close.addActionListener(new CloseButtonListener());
        setToolBar(tlb);
        // setBorder(BorderFactory.createEtchedBorder());
        setBorder(BorderFactory.createEmptyBorder());
        // setBorder(BorderFactory.createMatteBorder(1,1,1,1,java.awt.Color.green));
        // setPreferredSize(new java.awt.Dimension
        // (GUIGlobals.SPLIT_PANE_DIVIDER_LOCATION, 200));
        // Util.pr(""+GUIGlobals.SPLIT_PANE_DIVIDER_LOCATION);
    }

    /**
     * Alternative constructor that accepts an Icon directly for better SVG
     * support
     */
    public SidePaneComponent(SidePaneManager manager, Icon icon, String title) {
        super(convertIconToImageIcon(icon), title);
        this.manager = manager;
        setSelected(true);
        JToolBar tlb = new JToolBar();
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setBorder(null);
        up.setMargin(new Insets(0, 0, 0, 0));
        down.setMargin(new Insets(0, 0, 0, 0));
        up.setBorder(null);
        down.setBorder(null);
        up.addActionListener(new UpButtonListener());
        down.addActionListener(new DownButtonListener());
        tlb.setFloatable(false);
        tlb.add(up);
        tlb.add(down);
        tlb.add(close);
        close.addActionListener(new CloseButtonListener());
        setToolBar(tlb);
        setBorder(BorderFactory.createEmptyBorder());
    }

    /**
     * Convert URL to ImageIcon with SVG support
     */
    private static ImageIcon getIconFromUrl(URL url) {
        if (url == null) {
            return null;
        }

        // Try to detect if it's an SVG file
        if (url.toString().toLowerCase().endsWith(".svg")) {
            try {
                // Use FlatSVGIcon for SVG files
                com.formdev.flatlaf.extras.FlatSVGIcon svgIcon = new com.formdev.flatlaf.extras.FlatSVGIcon(url);
                return convertIconToImageIcon(svgIcon);
            } catch (Exception e) {
                System.err.println("Failed to load SVG icon: " + url + " " + e.getMessage());
                // Fall back to regular ImageIcon
            }
        }

        // Default to regular ImageIcon for PNG/GIF files
        return new ImageIcon(url);
    }

    /**
     * Convert any Icon to ImageIcon for compatibility
     */
    private static ImageIcon convertIconToImageIcon(Icon icon) {
        if (icon == null) {
            return null;
        }

        if (icon instanceof ImageIcon) {
            return (ImageIcon) icon;
        } else {
            // Convert generic Icon to ImageIcon
            java.awt.Image image = GUIGlobals.iconToImage(icon);
            return image != null ? new ImageIcon(image) : null;
        }
    }

    /**
     * Get icon as ImageIcon for button compatibility
     */
    private static ImageIcon getIconAsImageIcon(String iconName) {
        // First try SVG icon
        Icon svgIcon = GUIGlobals.getIcon(iconName, GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (svgIcon != null) {
            return convertIconToImageIcon(svgIcon);
        } else {
            // Fallback to legacy icon
            return GUIGlobals.getImageIcon(iconName);
        }
    }

    public void hideAway() {
        manager.hideComponent(this);
    }

    public void moveUp() {
        manager.moveUp(this);
    }

    public void moveDown() {
        manager.moveDown(this);
    }

    /**
     * Used by SidePaneManager only, to keep track of visibility.
     *
     */
    void setVisibility(boolean vis) {
        visible = vis;
    }

    /**
     * Used by SidePaneManager only, to keep track of visibility.
     *
     */
    boolean hasVisibility() {
        return visible;
    }

    public void setActiveBasePanel(BasePanel panel) {
        this.panel = panel;
    }

    public BasePanel getActiveBasePanel() {
        return panel;
    }

    /**
     * Override this method if the component needs to make any changes before it
     * can close.
     */
    public void componentClosing() {

    }

    /**
     * Override this method if the component needs to do any actions when
     * opening.
     */
    public void componentOpening() {

    }

    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    class CloseButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            hideAway();
        }
    }

    class UpButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            moveUp();
        }
    }

    class DownButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            moveDown();
        }
    }

    /**
     * Updates the UI for theme changes. This handles both standard Swing
     * components and JGoodies SimpleInternalFrame properly.
     */
    public void updateUIForThemeChange() {
        try {
            // Update the SimpleInternalFrame (JGoodies component)
            updateUI();

            // Update all child components
            SwingUtilities.updateComponentTreeUI(this);

            // Update toolbar buttons with new theme icons
            updateButtonIcons();

            // Revalidate to ensure proper layout
            revalidate();
            repaint();

        } catch (Exception e) {
            System.err.println("Error updating side pane component UI: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Update button icons to match the current theme
     */
    private void updateButtonIcons() {
        // Update close button icon
        Icon closeIcon = GUIGlobals.getIcon("close", GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (closeIcon != null) {
            close.setIcon(convertIconToImageIcon(closeIcon));
        }

        // Update up button icon  
        Icon upIcon = GUIGlobals.getIcon("up", GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (upIcon != null) {
            up.setIcon(convertIconToImageIcon(upIcon));
        }

        // Update down button icon
        Icon downIcon = GUIGlobals.getIcon("down", GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (downIcon != null) {
            down.setIcon(convertIconToImageIcon(downIcon));
        }
    }

}
