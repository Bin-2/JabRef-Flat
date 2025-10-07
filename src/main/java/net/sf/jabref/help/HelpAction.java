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
package net.sf.jabref.help;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.net.URL;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.KeyStroke;

import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.MnemonicAwareAction;

/**
 * This Action keeps a reference to a URL. When activated, it shows the help
 * Dialog unless it is already visible, and shows the URL in it.
 */
public class HelpAction extends MnemonicAwareAction {
    
    private HelpDialog diag;
    
    private Class resourceOwner = null;
    
    private String helpFile;
    
    public HelpAction(HelpDialog diag, String helpFile) {
        super(getHelpImageIcon());
        putValue(NAME, "Help");
        this.diag = diag;
        this.helpFile = helpFile;
    }
    
    public HelpAction(HelpDialog diag, String helpFile, String tooltip) {
        super(getHelpImageIcon());
        putValue(NAME, "Help");
        putValue(SHORT_DESCRIPTION, Globals.lang(tooltip));
        this.diag = diag;
        this.helpFile = helpFile;
    }
    
    public HelpAction(HelpDialog diag, String helpFile, String tooltip, URL iconFile) {
        super(getHelpImageIcon()); // Use SVG icon converted to ImageIcon
        putValue(NAME, "Help");
        putValue(SHORT_DESCRIPTION, Globals.lang(tooltip));
        this.diag = diag;
        this.helpFile = helpFile;
    }
    
    public HelpAction(String title, HelpDialog diag, String helpFile, String tooltip) {
        super(getHelpImageIcon());
        putValue(NAME, title);
        putValue(SHORT_DESCRIPTION, Globals.lang(tooltip));
        this.diag = diag;
        this.helpFile = helpFile;
    }
    
    public HelpAction(String title, HelpDialog diag, String helpFile, String tooltip, KeyStroke key) {
        super(getHelpImageIcon());
        putValue(NAME, title);
        putValue(SHORT_DESCRIPTION, Globals.lang(tooltip));
        putValue(ACCELERATOR_KEY, key);
        this.diag = diag;
        this.helpFile = helpFile;
    }
    
    public HelpAction(String title, HelpDialog diag, String helpFile, String tooltip, URL iconFile) {
        super(getHelpImageIcon()); // Use SVG icon converted to ImageIcon
        putValue(NAME, title);
        putValue(SHORT_DESCRIPTION, Globals.lang(tooltip));
        this.diag = diag;
        this.helpFile = helpFile;
    }

    /**
     * Get SVG help icon converted to ImageIcon for MnemonicAwareAction
     * compatibility
     */
    private static ImageIcon getHelpImageIcon() {
        // First try to get SVG icon and convert to ImageIcon
        Icon svgIcon = GUIGlobals.getIcon("help", GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (svgIcon != null) {
            return convertIconToImageIcon(svgIcon);
        } else {
            // Fallback to legacy PNG icon
            System.err.println("Warning: SVG help icon not found, falling back to legacy icon");
            return GUIGlobals.getImageIcon("help");
        }
    }

    /**
     * Convert any Icon to ImageIcon for compatibility with MnemonicAwareAction
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
     * Get help icon with specific size for different UI contexts
     */
    private static Icon getHelpIcon(int width, int height) {
        Icon svgIcon = GUIGlobals.getIcon("help", width, height);
        if (svgIcon != null) {
            return svgIcon;
        } else {
            // Fallback to legacy method if SVG not available
            ImageIcon legacyIcon = GUIGlobals.getImageIcon("help");
            if (legacyIcon != null) {
                // Scale the legacy icon to the requested size
                return new ImageIcon(legacyIcon.getImage().getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH));
            }
            return null;
        }
    }
    
    public void setResourceOwner(Class resourceOwner) {
        this.resourceOwner = resourceOwner;
    }
    
    public JButton getIconButton() {
        JButton hlp = new JButton(this);
        hlp.setText(null);
        hlp.setPreferredSize(new Dimension(GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE));

        // Use toolbar-sized icon for the button
        Icon buttonIcon = getHelpIcon(GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (buttonIcon != null) {
            hlp.setIcon(buttonIcon);
        }
        
        return hlp;
    }
    
    public void setHelpFile(String helpFile) {
        this.helpFile = helpFile;
    }
    
    public void actionPerformed(ActionEvent e) {
        if (resourceOwner == null) {
            diag.showPage(helpFile);
        } else {
            diag.showPage(helpFile, resourceOwner);
        }
    }
}
