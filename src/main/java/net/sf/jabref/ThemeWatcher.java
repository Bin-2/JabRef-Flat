/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.sf.jabref;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.sf.jabref.gui.ThemeAwareComponent;

/**
 *
 * @author bin
 */
public class ThemeWatcher {

    private static final Set<ThemedAction> registered = Collections.newSetFromMap(new WeakHashMap<>());

    public static void register(ThemedAction action) {
        registered.add(action);
    }

    public static void unregister(ThemedAction action) {
        registered.remove(action);
    }

    public static void notifyThemeChanged() {
        for (ThemedAction action : registered) {
            // System.out.println("notifyThemeChanged");
            action.onThemeChanged();
        }

        for (ThemeAwareComponent c : listeners) {
            c.onThemeChanged();
        }
    }

    private static final Set<ThemeAwareComponent> listeners = Collections.newSetFromMap(new WeakHashMap<>());

    public static void register(ThemeAwareComponent c) {
        listeners.add(c);
    }

    public static void unregister(ThemeAwareComponent c) {
        listeners.remove(c);
    }

}
