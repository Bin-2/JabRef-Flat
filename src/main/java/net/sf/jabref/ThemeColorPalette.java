/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package net.sf.jabref;

import java.awt.Color;

import javax.swing.UIManager;

/**
 * Provides theme-aware defaults for semantic application colors.
 *
 * @author bin
 */
public final class ThemeColorPalette {

    private static final Color[] LIGHT_MARK_COLORS = {
        new Color(255, 255, 180),
        new Color(255, 220, 180),
        new Color(255, 180, 160),
        new Color(255, 120, 120),
        new Color(255, 75, 75),
        new Color(220, 255, 220)
    };

    private static final float[] DARK_MARK_BLEND = {
        0.22f,
        0.25f,
        0.28f,
        0.32f,
        0.36f,
        0.24f
    };

    private static Boolean semanticColorsPreview;

    private ThemeColorPalette() {
    }

    public static Color getMarkColor(int level) {
        if ((level < 0) || (level >= LIGHT_MARK_COLORS.length)) {
            throw new IllegalArgumentException("Invalid mark color level: " + level);
        }

        Color lightColor = LIGHT_MARK_COLORS[level];
        if (!ThemeManager.isDarkTheme()) {
            return lightColor;
        }

        Color tableBackground = UIManager.getColor("Table.background");
        if (tableBackground == null) {
            tableBackground = UIManager.getColor("Panel.background");
        }
        if (tableBackground == null) {
            tableBackground = new Color(55, 55, 55);
        }

        return blend(tableBackground, lightColor, DARK_MARK_BLEND[level]);
    }

    private static Color blend(Color base, Color overlay, float overlayWeight) {
        float weight = Math.max(0.0f, Math.min(1.0f, overlayWeight));

        int red = Math.round((1.0f - weight) * base.getRed() + weight * overlay.getRed());
        int green = Math.round((1.0f - weight) * base.getGreen() + weight * overlay.getGreen());
        int blue = Math.round((1.0f - weight) * base.getBlue() + weight * overlay.getBlue());

        return new Color(red, green, blue);
    }

    public static boolean isSemanticColorsEnabled() {
        if (semanticColorsPreview != null) {
            return semanticColorsPreview;
        }

        return Globals.prefs.getBoolean(
                JabRefPreferences.USE_THEME_SEMANTIC_COLORS);
    }

    public static void setSemanticColorsPreview(boolean enabled) {
        semanticColorsPreview = enabled;
    }

    public static void clearSemanticColorsPreview() {
        semanticColorsPreview = null;
    }

}
