/*  Copyright (C) 2012 JabRef contributors.
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
package net.sf.jabref.specialfields;

import java.util.ArrayList;

import javax.swing.ImageIcon;

import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;

public class ReadStatus extends SpecialField {

    private static ReadStatus INSTANCE = null;

    public ReadStatus() {
        ArrayList<SpecialFieldValue> values = new ArrayList<SpecialFieldValue>();
        values.add(new SpecialFieldValue(this, Globals.lang("null"), "clearReadStatus", Globals.lang("Clear read status"), null, Globals.lang("No read status information")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("read"), "setReadStatusToRead", Globals.lang("Set read status to read"), "readStatusRead", Globals.lang("Read status read")));
        values.add(new SpecialFieldValue(this, Globals.lang("skimmed"), "setReadStatusToSkimmed", Globals.lang("Set read status to skimmed"), null, Globals.lang("Read status skimmed")));
        this.setValues(values);
        TEXT_DONE_PATTERN = "Set read status to '%0' for %1 entries";
    }

    public static ReadStatus getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ReadStatus();
        }
        return INSTANCE;
    }

    public String getFieldName() {
        return SpecialFieldsUtils.FIELDNAME_READ;
    }

    public ImageIcon getRepresentingIcon() {
        return GUIGlobals.getImageIcon("readstatus");
    }

    public String getToolTip() {
        return Globals.lang("Read status");
    }

    public String getMenuString() {
        return Globals.lang("Read status");
    }
}
