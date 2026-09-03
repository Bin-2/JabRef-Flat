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

import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JLabel;

import net.sf.jabref.GUIGlobals;
import net.sf.jabref.JabRefFrame;

public class SpecialFieldValue {

    private final SpecialField field;

    // keyword used at keyword field
    private final String keyword;

    // action belonging to this value
    private final String actionName;

    // localized menu string used at menu / button
    private String menuString;

    private SpecialFieldAction action = null;

    private SpecialFieldMenuAction menuAction = null;

    private final ImageIcon icon;

    private final String iconName;

    private ImageIcon themeIcon;

    private long iconThemeVersion = Long.MIN_VALUE;

    private String toolTipText;

    // value when used in a separate vield
    //private String fieldValue; 
    /**
     *
     * @param field The special field this value is a value of
     * @param keyword - The keyword to be used at BibTex's keyword field
     * @param actionName - the action to call
     * @param menuString - the string to display at a menu
     * @param icon - the icon of this value
     * @param toolTipText - the tool tip text
     */
    public SpecialFieldValue(
            SpecialField field,
            String keyword,
            String actionName,
            String menuString,
            ImageIcon icon,
            String toolTipText) {
        this(field, keyword, actionName, menuString, icon, null, toolTipText);
    }

    private SpecialFieldValue(
            SpecialField field,
            String keyword,
            String actionName,
            String menuString,
            ImageIcon icon,
            String iconName,
            String toolTipText) {
        this.field = field;
        this.keyword = keyword;
        this.actionName = actionName;
        this.menuString = menuString;
        this.icon = icon;
        this.iconName = iconName;
        this.toolTipText = toolTipText;
    }

    public static SpecialFieldValue withThemeIcon(
            SpecialField field,
            String keyword,
            String actionName,
            String menuString,
            String iconName,
            String toolTipText) {
        return new SpecialFieldValue(
                field,
                keyword,
                actionName,
                menuString,
                null,
                iconName,
                toolTipText);
    }

    public String getKeyword() {
        return this.keyword;
    }

    public String getActionName() {
        return this.actionName;
    }

    public String getMenuString() {
        return this.menuString;
    }

    public JLabel createLabel() {
        JLabel label = new JLabel(this.getIcon());
        label.setToolTipText(this.toolTipText);
        return label;
    }

    public String getFieldValue() {
        return this.keyword;
    }

    public ImageIcon getIcon() {
        if (this.iconName == null) {
            return this.icon;
        }

        long currentIconThemeVersion = GUIGlobals.getIconThemeVersion();
        if (this.iconThemeVersion != currentIconThemeVersion) {
            this.themeIcon = GUIGlobals.getImageIcon(this.iconName);
            this.iconThemeVersion = currentIconThemeVersion;
        }
        return this.themeIcon;
    }

    public String getToolTipText() {
        return this.toolTipText;
    }

    public SpecialFieldAction getAction(JabRefFrame frame) {
        if (this.action == null) {
            action = new SpecialFieldAction(
                    frame,
                    this.field,
                    this.getFieldValue(),
                    // if field contains only one value, it has to be nulled
                    // otherwise, another setting does not empty the field
                    this.field.getValues().size() == 1,
                    this.getMenuString(),
                    this.field.TEXT_DONE_PATTERN);
        }
        return action;
    }

    public SpecialFieldMenuAction getMenuAction(JabRefFrame frame) {
        if (this.menuAction == null) {
            this.menuAction = new SpecialFieldMenuAction(this, frame);
        } else {
            this.menuAction.putValue(Action.SMALL_ICON, this.getIcon());
        }
        return this.menuAction;
    }

}
