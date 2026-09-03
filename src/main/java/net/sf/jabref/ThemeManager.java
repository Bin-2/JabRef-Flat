package net.sf.jabref;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme;

import java.awt.Color;

import javax.swing.UIManager;

/**
 * Provides information about the currently active application theme.
 *
 * @author bin
 */
public final class ThemeManager {

    public enum ThemeType {
        LIGHT,
        DARK
    }

    public static final String FLAT_LIGHT = "FlatLight";
    public static final String FLAT_DARK = "FlatDark";
    public static final String SOLARIZED_LIGHT = "FlatSolarizedLightIJTheme";
    public static final String CARBON_DARK = "FlatCarbonIJTheme";

    public static final String DEFAULT_THEME = FLAT_LIGHT;

    private static final String[] AVAILABLE_THEMES = {
        FLAT_LIGHT,
        FLAT_DARK,
        SOLARIZED_LIGHT,
        CARBON_DARK
    };

    private ThemeManager() {
    }

    public static String[] getAvailableThemes() {
        return AVAILABLE_THEMES.clone();
    }

    public static String normalizeThemeName(String themeName) {
        if ((themeName == null) || themeName.trim().isEmpty()) {
            return DEFAULT_THEME;
        }

        String theme = themeName.trim();

        switch (theme) {
            case "com.formdev.flatlaf.FlatLightLaf":
                return FLAT_LIGHT;

            case "com.formdev.flatlaf.FlatDarkLaf":
                return FLAT_DARK;

            case "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme":
                return SOLARIZED_LIGHT;

            case "com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme":
                return CARBON_DARK;

            default:
                return theme;
        }
    }

    public static void applyTheme(String themeName) throws Exception {
        String theme = normalizeThemeName(themeName);

        switch (theme) {
            case FLAT_LIGHT:
                UIManager.setLookAndFeel(new FlatLightLaf());
                break;

            case SOLARIZED_LIGHT:
                UIManager.setLookAndFeel(new FlatSolarizedLightIJTheme());
                break;

            case FLAT_DARK:
                UIManager.setLookAndFeel(new FlatDarkLaf());
                break;

            case CARBON_DARK:
                UIManager.setLookAndFeel(new FlatCarbonIJTheme());
                break;

            default:
                UIManager.setLookAndFeel(theme);
                break;
        }
    }

    public static ThemeType getThemeType() {
        Color background = UIManager.getColor("Table.background");

        if (background == null) {
            background = UIManager.getColor("Panel.background");
        }

        if (background == null) {
            return ThemeType.LIGHT;
        }

        double luminance = (0.299 * background.getRed()
                + 0.587 * background.getGreen()
                + 0.114 * background.getBlue()) / 255.0;

        return luminance < 0.5 ? ThemeType.DARK : ThemeType.LIGHT;
    }

    public static boolean isDarkTheme() {
        return getThemeType() == ThemeType.DARK;
    }
}