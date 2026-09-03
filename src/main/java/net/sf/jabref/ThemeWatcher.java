package net.sf.jabref;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;

import net.sf.jabref.gui.MainTable;
import net.sf.jabref.gui.ThemeAwareComponent;

/**
 *
 * @author bin
 */
public class ThemeWatcher {

    private static final Set<ThemedAction> registered = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<ThemeAwareComponent> listeners = Collections.newSetFromMap(new WeakHashMap<>());

    public static void register(ThemedAction action) {
        synchronized (registered) {
            registered.add(action);
        }
    }

    public static void unregister(ThemedAction action) {
        synchronized (registered) {
            registered.remove(action);
        }
    }

    public static void register(ThemeAwareComponent component) {
        synchronized (listeners) {
            listeners.add(component);
        }
    }

    public static void unregister(ThemeAwareComponent component) {
        synchronized (listeners) {
            listeners.remove(component);
        }
    }

    public static void notifyThemeChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            notifyThemeChangedOnEdt();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(ThemeWatcher::notifyThemeChangedOnEdt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for theme change notification.",
                    e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            if (cause instanceof Error) {
                throw (Error) cause;
            }

            throw new IllegalStateException(
                    "Theme change notification failed.",
                    cause);
        }
    }

    private static void notifyThemeChangedOnEdt() {
        GUIGlobals.clearTableIconCache();
        GUIGlobals.initTableIcons();
        MainTable.updateRenderers();

        ThemedAction[] actions;
        synchronized (registered) {
            actions = registered.toArray(new ThemedAction[0]);
        }

        for (ThemedAction action : actions) {
            try {
                action.onThemeChanged();
            } catch (RuntimeException ex) {
                logNotificationFailure("ThemedAction", action, ex);
            }
        }

        ThemeAwareComponent[] components;
        synchronized (listeners) {
            components = listeners.toArray(new ThemeAwareComponent[0]);
        }

        for (ThemeAwareComponent component : components) {
            try {
                component.onThemeChanged();
            } catch (RuntimeException ex) {
                logNotificationFailure("ThemeAwareComponent", component, ex);
            }
        }
    }

    private static void logNotificationFailure(String listenerType, Object listener, RuntimeException ex) {
        String listenerClass = listener == null ? "<null>" : listener.getClass().getName();
        Globals.logger("Theme update failed for " + listenerType + " "
                + listenerClass + ": " + ex);
    }
}
