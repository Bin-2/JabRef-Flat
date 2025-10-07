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
package net.sf.jabref.plugin;

import net.sf.jabref.JabRefFrame;
import net.sf.jabref.MnemonicAwareAction;
import net.sf.jabref.Globals;
import net.sf.jabref.GUIGlobals;

import java.awt.event.ActionEvent;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/**
 * Created by IntelliJ IDEA. User: alver Date: Mar 27, 2009 Time: 11:33:56 PM To
 * change this template use File | Settings | File Templates.
 */
public class PluginInstallerAction extends MnemonicAwareAction {

    private JabRefFrame frame;

    public PluginInstallerAction(JabRefFrame frame) {
        super(getPluginImageIcon());
        this.frame = frame;
        putValue(NAME, Globals.menuTitle("Manage plugins"));
    }

    public void actionPerformed(ActionEvent actionEvent) {
        ManagePluginsDialog mpd = new ManagePluginsDialog(frame);
        mpd.setVisible(true);
    }

    /**
     * Get SVG plugin icon converted to ImageIcon for MnemonicAwareAction
     * compatibility
     */
    private static ImageIcon getPluginImageIcon() {
        // First try to get SVG icon and convert to ImageIcon
        Icon svgIcon = GUIGlobals.getIcon("plugin", GUIGlobals.MENU_ICON_SIZE, GUIGlobals.MENU_ICON_SIZE);
        if (svgIcon != null) {
            return convertIconToImageIcon(svgIcon);
        } else {
            // Fallback to legacy PNG icon
            System.err.println("Warning: SVG plugin icon not found, falling back to legacy icon");
            return GUIGlobals.getImageIcon("plugin");
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
            // Convert generic Icon to ImageIcon using the existing utility method
            java.awt.Image image = GUIGlobals.iconToImage(icon);
            return image != null ? new ImageIcon(image) : null;
        }
    }

}
