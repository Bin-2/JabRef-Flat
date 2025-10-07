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
import net.sf.jabref.undo.NamedCompound;

import javax.swing.*;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import net.sf.jabref.gui.ThemeAwareComponent;

/**
 *
 */
public class MarkEntriesAction extends AbstractWorker implements ActionListener, ThemeAwareComponent {

    private final JabRefFrame frame;
    final int level;
    private final JMenuItem menuItem;
    private int besLength = 0;

    public MarkEntriesAction(JabRefFrame frame, int level) {
        this.frame = frame;
        this.level = level;

        menuItem = new JMenuItem("               ");
        menuItem.setMnemonic(String.valueOf(level + 1).charAt(0));

        updateMenuColor(); // Initial color setup
        menuItem.setOpaque(true); // Changed to true to show background
        menuItem.addActionListener(this);

        // Register for theme changes
        ThemeWatcher.register(this);
    }

    public JMenuItem getMenuItem() {
        return menuItem;
    }

    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        try {
            this.init();
            getWorker().run();
            getCallBack().update();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void run() {
        BasePanel panel = frame.basePanel();
        BibtexEntry[] bes = panel.getSelectedEntries();

        // used at update() to determine output string
        besLength = bes.length;

        if (bes.length != 0) {
            NamedCompound ce = new NamedCompound(Globals.lang("Mark entries"));
            for (BibtexEntry be : bes) {
                Util.markEntry(be, level + 1, false, ce);
            }
            ce.end();
            panel.undoManager.addEdit(ce);
        }
    }

    @Override
    public void update() {
        String outputStr;
        switch (besLength) {
            case 0:
                outputStr = Globals.lang("No entries selected.");
                break;
            case 1:
                frame.basePanel().markBaseChanged();
                outputStr = Globals.lang("Marked selected entry");
                break;
            default:
                frame.basePanel().markBaseChanged();
                outputStr = Globals.lang("Marked all %0 selected entries", Integer.toString(besLength));
                break;
        }
        frame.output(outputStr);
    }

    @Override
    public void onThemeChanged() {
        // Update menu color when theme changes - always use the original marking color
        updateMenuColor();
    }

    private void updateMenuColor() {
        // Always use the original marking colors, don't let theme changes affect them
        Color markColor = Globals.prefs.getColor("markedEntryBackground" + level);
        // System.out.println(markColor);
        if (markColor != null) {
            menuItem.setBackground(markColor);

            // Ensure text is readable against the background
            if (isDarkColor(markColor)) {
                menuItem.setForeground(Color.WHITE);
            } else {
                menuItem.setForeground(Color.BLACK);
            }
        }
    }

    private boolean isDarkColor(Color color) {
        // Simple luminance calculation
        double luminance = (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue()) / 255;
        return luminance < 0.5;
    }

    public void cleanup() {
        ThemeWatcher.unregister(this);
    }

}
