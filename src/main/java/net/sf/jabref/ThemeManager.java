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

        // Keep JMenuBar below the title bar instead of embedding it
        // into FlatLaf's window title pane.
        // UIManager.put("TitlePane.menuBarEmbedded", Boolean.FALSE); //*** This is not helpful !!!
        //
        // fix Window Title alignment issue
        UIManager.put("TitlePane.centerTitle", Boolean.FALSE);
        UIManager.put("TitlePane.centerTitleIfMenuBarEmbedded", Boolean.FALSE);

        // fix Window Title (too) pale foreground
        Color titleForeground = UIManager.getColor("MenuBar.foreground");
        Color titleBackground = UIManager.getColor("MenuBar.background");

        if (titleForeground == null) {
            titleForeground = UIManager.getColor("Label.foreground");
        }

        UIManager.put("TitlePane.foreground", titleForeground);
        UIManager.put("TitlePane.embeddedForeground", titleForeground);
        UIManager.put(
                "TitlePane.inactiveForeground",
                blend(titleForeground, titleBackground, 0.75));
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

    // blend color for inactive setting
    private static Color blend(Color foreground, Color background, double foregroundWeight) {
        double backgroundWeight = 1.0 - foregroundWeight;

        return new Color(
                (int) Math.round(foreground.getRed() * foregroundWeight
                        + background.getRed() * backgroundWeight),
                (int) Math.round(foreground.getGreen() * foregroundWeight
                        + background.getGreen() * backgroundWeight),
                (int) Math.round(foreground.getBlue() * foregroundWeight
                        + background.getBlue() * backgroundWeight));
    }
}
