/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.sf.jabref;

import javax.swing.AbstractAction;

/**
 *
 * @author bin
 */
public abstract class ThemedAction extends AbstractAction {

    private final String iconKey;

    protected ThemedAction(String name, String iconKey) {
        super(name, GUIGlobals.getImage(iconKey));
        this.iconKey = iconKey;
        putValue(SHORT_DESCRIPTION, name);

        ThemeWatcher.register(this);
    }

    public void updateIcon() {
        putValue(SMALL_ICON, GUIGlobals.getImage(iconKey));
    }

    /**
     * Called automatically when the theme changes.
     * Subclasses can override this to add more custom logic.
     */
    public void onThemeChanged() {
        // System.out.println("onThemeChanged");
        updateIcon();  // default behavior: reload icon
    }

    public void dispose() {
        ThemeWatcher.unregister(this);
    }
}
