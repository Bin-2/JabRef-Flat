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
package net.sf.jabref.export;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import net.sf.jabref.BasePanel;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.JabRefFrame;
import net.sf.jabref.MnemonicAwareAction;

/**
 * Saves all open databases.
 */
public class SaveAllAction extends MnemonicAwareAction {

    private final JabRefFrame frame;

    /**
     * Creates a new instance of SaveAllAction.
     */
    public SaveAllAction(JabRefFrame frame) {
        super(GUIGlobals.getImage("saveAllClean"));
        this.frame = frame;
        putValue(ACCELERATOR_KEY, Globals.prefs.getKey("Save all"));
        putValue(SHORT_DESCRIPTION, Globals.lang("Save all open databases"));
        putValue(NAME, "Save all");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<BasePanel> panels = new ArrayList<>();
        for (int i = 0; i < frame.getTabbedPane().getTabCount(); i++) {
            BasePanel panel = frame.baseAt(i);
            if (panel.isBaseChanged()) {
                panels.add(panel);
            }
        }

        if (panels.isEmpty()) {
            frame.output(Globals.lang("Save all finished."));
            return;
        }

        frame.output(Globals.lang("Saving all databases..."));

        int saved = 0;
        int cancelled = 0;
        int failed = 0;

        for (BasePanel panel : panels) {
            if (panel.getFile() == null) {
                frame.showBasePanel(panel);
            }

            SaveDatabaseAction saveAction = new SaveDatabaseAction(panel);
            try {
                saveAction.runCommand();
            } catch (Throwable ex) {
                failed++;
                ex.printStackTrace();
                continue;
            }

            if (saveAction.isSuccess()) {
                saved++;
            } else if (saveAction.isCancelled()) {
                cancelled++;
            } else {
                failed++;
            }
        }

        frame.updateSaveIconState();

        if (failed == 0 && cancelled == 0) {
            frame.output(Globals.lang("Save all finished."));
        } else {
            frame.output(Globals.lang("Save all finished.")
                    + " " + saved + "/" + panels.size()
                    + " saved, " + cancelled + " cancelled, " + failed + " failed.");
        }
    }
}
