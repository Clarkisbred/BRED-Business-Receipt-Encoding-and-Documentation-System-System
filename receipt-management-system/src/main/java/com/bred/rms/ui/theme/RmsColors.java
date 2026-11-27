package com.bred.rms.ui.theme;

import java.awt.Color;

/** Single source of truth for brand colors. Theme-dependent values resolve through ThemeManager. */
public final class RmsColors {
    /** Prevents instantiation of this utility class. */
    private RmsColors() { }

    /** Default palette values retained for callers that need fixed colors. */
    public static final Color PRIMARY = Color.decode("#1F5FBF");
    public static final Color PRIMARY_DARK = Color.decode("#164687");
    public static final Color PRIMARY_LIGHT = Color.decode("#E8F0FC");
    public static final Color SUCCESS = Color.decode("#218838");
    public static final Color WARNING = Color.decode("#E6A700");
    public static final Color DANGER = Color.decode("#D62828");
    public static final Color INFO = Color.decode("#2474C7");
    public static final Color SECONDARY_TEXT = Color.decode("#747B87");

    public static final Color DARK_BACKGROUND = Color.decode("#15171C");
    public static final Color DARK_SURFACE = Color.decode("#20232B");
    public static final Color DARK_TEXT = Color.decode("#F3F4F6");
    public static final Color LIGHT_BACKGROUND = Color.decode("#F5F6F8");
    public static final Color LIGHT_SURFACE = Color.decode("#FFFFFF");
    public static final Color LIGHT_TEXT = Color.decode("#202124");
    public static final Color GLASS_BACKGROUND = Color.decode("#141625");
    public static final Color GLASS_SIDEBAR = Color.decode("#191D32");
    public static final Color GLASS_HEADER = Color.decode("#20243A");
    public static final Color GLASS_CARD = Color.decode("#252A43");
    public static final Color GLASS_ELEVATED = Color.decode("#2D3350");
    public static final Color GLASS_BORDER = Color.decode("#3A405D");
    public static final Color GLASS_ACCENT_PURPLE = Color.decode("#8B7CF6");
    public static final Color GLASS_ACCENT_BLUE = Color.decode("#5B9CFF");
    public static final Color GLASS_ACCENT_GOLD = Color.decode("#F5B52B");

    /** Returns the readable primary accent for the selected appearance and mode. */
    public static Color primary() {
        if (ThemeManager.customAccent() != null) return ThemeManager.customAccent();
        if (ThemeManager.appearance() == ThemeManager.Appearance.MODERN_GLASS) return GLASS_ACCENT_PURPLE;
        if (ThemeManager.appearance() == ThemeManager.Appearance.MIDNIGHT_BLUE) return Color.decode("#5B9CFF");
        if (ThemeManager.appearance() == ThemeManager.Appearance.CLEAN_LIGHT) return Color.decode("#2563EB");
        if (ThemeManager.appearance() == ThemeManager.Appearance.GRAPHITE) return GLASS_ACCENT_GOLD;
        if (ThemeManager.appearance() == ThemeManager.Appearance.BREDUCK_SIGNATURE) {
            return ThemeManager.isDark() ? Color.decode("#F7A72E") : Color.decode("#995900");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.DUTERTES_SIGNATURE) {
            return Color.decode("#2E7D32");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.SHRIMPS_AUCLAIR) {
            return ThemeManager.isDark() ? Color.decode("#F48FB1") : Color.decode("#AD1457");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.COSMIC_VIOLET) {
            return ThemeManager.isDark() ? Color.decode("#CE93D8") : Color.decode("#6A1B9A");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.CHILLI_PEPPER_RED) {
            return ThemeManager.isDark() ? Color.decode("#EF9A9A") : Color.decode("#B71C1C");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MAHOGANY_WOOD) {
            return ThemeManager.isDark() ? Color.decode("#BCAAA4") : Color.decode("#5D4037");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MILLIONAIRES_GOLD) {
            return ThemeManager.isDark() ? Color.decode("#FFD54F") : Color.decode("#A66F00");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.SILVER_LINING) {
            return ThemeManager.isDark() ? Color.decode("#CFD8DC") : Color.decode("#546E7A");
        }
        return PRIMARY;
    }

    /** Returns the bright palette accent for emphasis and filled controls. */
    public static Color primaryAccent() {
        if (ThemeManager.customAccent() != null) return ThemeManager.customAccent();
        return switch (ThemeManager.appearance()) {
            case MODERN_GLASS -> GLASS_ACCENT_PURPLE;
            case MIDNIGHT_BLUE -> Color.decode("#5B9CFF");
            case CLEAN_LIGHT -> Color.decode("#2563EB");
            case GRAPHITE -> GLASS_ACCENT_GOLD;
            case BREDUCK_SIGNATURE -> Color.decode("#F7A72E");
            case DUTERTES_SIGNATURE -> ThemeManager.isDark()
                    ? Color.decode("#2E7D32") : Color.decode("#A5D6A7");
            case SHRIMPS_AUCLAIR -> ThemeManager.isDark()
                    ? Color.decode("#F48FB1") : Color.decode("#F8BBD0");
            case COSMIC_VIOLET -> ThemeManager.isDark()
                    ? Color.decode("#CE93D8") : Color.decode("#CE93D8");
            case CHILLI_PEPPER_RED -> ThemeManager.isDark()
                    ? Color.decode("#EF5350") : Color.decode("#EF9A9A");
            case MAHOGANY_WOOD -> ThemeManager.isDark()
                    ? Color.decode("#BCAAA4") : Color.decode("#BCAAA4");
            case MILLIONAIRES_GOLD -> ThemeManager.isDark()
                    ? Color.decode("#FFD54F") : Color.decode("#FFE082");
            case SILVER_LINING -> ThemeManager.isDark()
                    ? Color.decode("#CFD8DC") : Color.decode("#CFD8DC");
            case DEFAULT -> PRIMARY;
        };
    }

    /** Returns the darker primary accent used for emphasis. */
    public static Color primaryDark() {
        if (ThemeManager.customAccent() != null) {
            Color c = ThemeManager.customAccent();
            return c.darker();
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MODERN_GLASS) return Color.decode("#6C5CE7");
        if (ThemeManager.appearance() == ThemeManager.Appearance.MIDNIGHT_BLUE) return Color.decode("#2563EB");
        if (ThemeManager.appearance() == ThemeManager.Appearance.CLEAN_LIGHT) return Color.decode("#1D4ED8");
        if (ThemeManager.appearance() == ThemeManager.Appearance.GRAPHITE) return Color.decode("#D88E1D");
        if (ThemeManager.appearance() == ThemeManager.Appearance.BREDUCK_SIGNATURE) {
            return ThemeManager.isDark() ? Color.decode("#D88E1D") : Color.decode("#6F4000");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.DUTERTES_SIGNATURE) {
            return ThemeManager.isDark() ? Color.decode("#1B5E20") : Color.decode("#1B5E20");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.SHRIMPS_AUCLAIR) {
            return ThemeManager.isDark() ? Color.decode("#C2185B") : Color.decode("#880E4F");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.COSMIC_VIOLET) {
            return ThemeManager.isDark() ? Color.decode("#7B1FA2") : Color.decode("#4A148C");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.CHILLI_PEPPER_RED) {
            return ThemeManager.isDark() ? Color.decode("#C62828") : Color.decode("#8E0000");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MAHOGANY_WOOD) {
            return ThemeManager.isDark() ? Color.decode("#8D6E63") : Color.decode("#3E2723");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MILLIONAIRES_GOLD) {
            return ThemeManager.isDark() ? Color.decode("#FFCA28") : Color.decode("#7A4E00");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.SILVER_LINING) {
            return ThemeManager.isDark() ? Color.decode("#90A4AE") : Color.decode("#37474F");
        }
        return PRIMARY_DARK;
    }

    /** Returns the selected-item highlight for the active appearance and mode. */
    public static Color primaryLight() {
        if (ThemeManager.appearance() == ThemeManager.Appearance.MODERN_GLASS)
            return ThemeManager.isDark() ? Color.decode("#302A50") : Color.decode("#EDE9FE");
        if (ThemeManager.appearance() == ThemeManager.Appearance.MIDNIGHT_BLUE)
            return ThemeManager.isDark() ? Color.decode("#1E3A5F") : Color.decode("#DBEAFE");
        if (ThemeManager.appearance() == ThemeManager.Appearance.CLEAN_LIGHT)
            return Color.decode("#DBEAFE");
        if (ThemeManager.appearance() == ThemeManager.Appearance.GRAPHITE)
            return ThemeManager.isDark() ? Color.decode("#4A4025") : Color.decode("#FFF3D6");
        if (ThemeManager.appearance() == ThemeManager.Appearance.BREDUCK_SIGNATURE) {
            return ThemeManager.isDark() ? Color.decode("#3B301C") : Color.decode("#FCE283");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.DUTERTES_SIGNATURE) {
            return ThemeManager.isDark() ? Color.decode("#263B2A") : Color.decode("#DDF1DE");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.SHRIMPS_AUCLAIR) {
            return ThemeManager.isDark() ? Color.decode("#4A2934") : Color.decode("#FCE4EC");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.COSMIC_VIOLET) {
            return ThemeManager.isDark() ? Color.decode("#3D2946") : Color.decode("#F3E5F5");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.CHILLI_PEPPER_RED) {
            return ThemeManager.isDark() ? Color.decode("#4A2929") : Color.decode("#FFEBEE");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MAHOGANY_WOOD) {
            return ThemeManager.isDark() ? Color.decode("#42322E") : Color.decode("#EFEBE9");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.MILLIONAIRES_GOLD) {
            return ThemeManager.isDark() ? Color.decode("#4A4025") : Color.decode("#FFF8E1");
        }
        if (ThemeManager.appearance() == ThemeManager.Appearance.SILVER_LINING) {
            return ThemeManager.isDark() ? Color.decode("#30383C") : Color.decode("#ECEFF1");
        }
        return ThemeManager.isDark() ? Color.decode("#30394B") : PRIMARY_LIGHT;
    }

    /** Returns a foreground color with sufficient contrast on the primary accent. */
    public static Color primaryForeground() {
        if (ThemeManager.customAccent() != null) {
            Color c = ThemeManager.customAccent();
            double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
            return luminance > 0.62 ? Color.decode("#202124") : Color.WHITE;
        }
        return switch (ThemeManager.appearance()) {
            case MODERN_GLASS, MIDNIGHT_BLUE, CLEAN_LIGHT -> Color.WHITE;
            case GRAPHITE -> Color.decode("#202124");
            case BREDUCK_SIGNATURE -> Color.decode("#202124");
            case DUTERTES_SIGNATURE -> ThemeManager.isDark() ? Color.WHITE : Color.decode("#173A1A");
            case SHRIMPS_AUCLAIR -> ThemeManager.isDark() ? Color.decode("#202124") : Color.decode("#650B30");
            case COSMIC_VIOLET -> ThemeManager.isDark() ? Color.decode("#202124") : Color.decode("#3A1450");
            case CHILLI_PEPPER_RED -> ThemeManager.isDark() ? Color.decode("#202124") : Color.decode("#641414");
            case MAHOGANY_WOOD -> ThemeManager.isDark() ? Color.decode("#202124") : Color.decode("#2E1A15");
            case MILLIONAIRES_GOLD -> ThemeManager.isDark() ? Color.decode("#202124") : Color.decode("#4A3500");
            case SILVER_LINING -> ThemeManager.isDark() ? Color.decode("#202124") : Color.decode("#263238");
            case DEFAULT -> Color.WHITE;
        };
    }

    /** Returns the background color for the active theme. */
    public static Color background() {
        if (ThemeManager.appearance() == ThemeManager.Appearance.MODERN_GLASS) return GLASS_BACKGROUND;
        if (ThemeManager.appearance() == ThemeManager.Appearance.MIDNIGHT_BLUE)
            return ThemeManager.isDark() ? Color.decode("#0B1F3A") : Color.decode("#F4F7FB");
        if (ThemeManager.appearance() == ThemeManager.Appearance.CLEAN_LIGHT)
            return Color.decode("#F8FAFC");
        if (ThemeManager.appearance() == ThemeManager.Appearance.GRAPHITE)
            return ThemeManager.isDark() ? Color.decode("#1D1D1F") : Color.decode("#F4F4F5");
        if (ThemeManager.isDark()) return DARK_BACKGROUND;
        return switch (ThemeManager.appearance()) {
            case MODERN_GLASS -> GLASS_BACKGROUND;
            case MIDNIGHT_BLUE -> Color.decode("#F4F7FB");
            case CLEAN_LIGHT -> Color.decode("#F8FAFC");
            case GRAPHITE -> Color.decode("#F4F4F5");
            case BREDUCK_SIGNATURE -> Color.decode("#FFF9E8");
            case DUTERTES_SIGNATURE -> Color.decode("#F1F8F1");
            case SHRIMPS_AUCLAIR -> Color.decode("#FFF7FA");
            case COSMIC_VIOLET -> Color.decode("#FAF7FC");
            case CHILLI_PEPPER_RED -> Color.decode("#FFF8F7");
            case MAHOGANY_WOOD -> Color.decode("#FBF8F6");
            case MILLIONAIRES_GOLD -> Color.decode("#FFFCF2");
            case SILVER_LINING -> Color.decode("#F8FAFB");
            case DEFAULT -> LIGHT_BACKGROUND;
        };
    }
    /** Returns the surface color for the active theme. */
    public static Color surface() {
        if (ThemeManager.appearance() == ThemeManager.Appearance.MODERN_GLASS) return GLASS_CARD;
        if (ThemeManager.appearance() == ThemeManager.Appearance.MIDNIGHT_BLUE)
            return ThemeManager.isDark() ? Color.decode("#12305A") : Color.WHITE;
        if (ThemeManager.appearance() == ThemeManager.Appearance.CLEAN_LIGHT) return Color.WHITE;
        if (ThemeManager.appearance() == ThemeManager.Appearance.GRAPHITE)
            return ThemeManager.isDark() ? Color.decode("#26272B") : Color.WHITE;
        if (ThemeManager.isDark()) return DARK_SURFACE;
        return switch (ThemeManager.appearance()) {
            case MODERN_GLASS -> GLASS_CARD;
            case MIDNIGHT_BLUE -> Color.WHITE;
            case CLEAN_LIGHT -> Color.WHITE;
            case GRAPHITE -> Color.WHITE;
            case BREDUCK_SIGNATURE -> Color.decode("#FFFDF5");
            case DUTERTES_SIGNATURE -> Color.decode("#FAFDFA");
            case SHRIMPS_AUCLAIR -> Color.decode("#FFFDFE");
            case COSMIC_VIOLET -> Color.decode("#FDFBFF");
            case CHILLI_PEPPER_RED -> Color.decode("#FFFDFD");
            case MAHOGANY_WOOD -> Color.decode("#FFFDFC");
            case MILLIONAIRES_GOLD -> Color.decode("#FFFDF7");
            case SILVER_LINING -> Color.decode("#FFFFFF");
            case DEFAULT -> LIGHT_SURFACE;
        };
    }
    /** Returns the primary text color for the active theme. */
    public static Color text() { return ThemeManager.isDark() ? DARK_TEXT : LIGHT_TEXT; }
    /** Returns the border color for the active theme. */
    public static Color border() {
        if (ThemeManager.appearance() == ThemeManager.Appearance.MODERN_GLASS) return GLASS_BORDER;
        if (ThemeManager.appearance() == ThemeManager.Appearance.MIDNIGHT_BLUE)
            return ThemeManager.isDark() ? Color.decode("#274A73") : Color.decode("#CBD5E1");
        if (ThemeManager.appearance() == ThemeManager.Appearance.CLEAN_LIGHT)
            return Color.decode("#E2E8F0");
        if (ThemeManager.appearance() == ThemeManager.Appearance.GRAPHITE)
            return ThemeManager.isDark() ? Color.decode("#404145") : Color.decode("#D4D4D8");
        if (ThemeManager.isDark()) return Color.decode("#343843");
        return switch (ThemeManager.appearance()) {
            case MODERN_GLASS -> GLASS_BORDER;
            case MIDNIGHT_BLUE -> Color.decode("#CBD5E1");
            case CLEAN_LIGHT -> Color.decode("#E2E8F0");
            case GRAPHITE -> Color.decode("#D4D4D8");
            case BREDUCK_SIGNATURE -> Color.decode("#EFE2B7");
            case DUTERTES_SIGNATURE -> Color.decode("#D1E4D2");
            case SHRIMPS_AUCLAIR -> Color.decode("#F4C7D8");
            case COSMIC_VIOLET -> Color.decode("#D9C3E3");
            case CHILLI_PEPPER_RED -> Color.decode("#F1C2C2");
            case MAHOGANY_WOOD -> Color.decode("#D7CCC8");
            case MILLIONAIRES_GOLD -> Color.decode("#E8D89A");
            case SILVER_LINING -> Color.decode("#CFD8DC");
            case DEFAULT -> Color.decode("#E1E3E8");
        };
    }
}