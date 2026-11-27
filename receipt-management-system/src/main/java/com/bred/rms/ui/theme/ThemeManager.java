package com.bred.rms.ui.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Window;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Installs FlatLaf in light or dark mode with a selectable color palette,
 * remembers the choices, and broadcasts theme changes to registered listeners.
 */
public final class ThemeManager {
    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static final String KEY_DARK = "theme.dark";
    private static final String KEY_APPEARANCE = "theme.appearance";
    private static final String KEY_ACCENT = "theme.accent";
    private static boolean dark;
    private static Appearance appearance = Appearance.DEFAULT;
    private static Color accentOverride;
    private static final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    /** Available application color palettes. */
    public enum Appearance {
        DEFAULT("Default"),
        MODERN_GLASS("Modern Glass"),
        MIDNIGHT_BLUE("Midnight Blue"),
        CLEAN_LIGHT("Clean Light"),
        GRAPHITE("Graphite"),
        BREDUCK_SIGNATURE("BreDuck's Signature"),
        DUTERTES_SIGNATURE("Duterte for 2028"),
        SHRIMPS_AUCLAIR("Shrimp's Auclair"),
        COSMIC_VIOLET("Cosmic Violet"),
        CHILLI_PEPPER_RED("Chilli Pepper Red"),
        MAHOGANY_WOOD("Mahogany wood"),
        MILLIONAIRES_GOLD("Millionaire's Gold"),
        SILVER_LINING("Silver Lining");

        private final String label;

        Appearance(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        private static Appearance fromPreference(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException | NullPointerException ex) {
                return DEFAULT;
            }
        }
    }

    /** Creates a theme manager instance. */
    private ThemeManager() { }

    /** Reports whether this theme manager is dark. */
    public static boolean isDark() { 
        return dark; 
    }

    /** Returns the selected color palette. */
    public static Appearance appearance() {
        return appearance;
    }

    /** Returns the optional user-selected accent color. */
    public static Color customAccent() {
        return accentOverride;
    }

    /** Register a callback whenever the theme changes. */
    public static void addThemeListener(Consumer<Boolean> listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** Removes theme listener. */
    public static void removeThemeListener(Consumer<Boolean> listener) {
        listeners.remove(listener);
    }

    /** Applies the saved theme and appearance preferences. */
    public static void installSaved() { 
        appearance = Appearance.fromPreference(PREFS.get(KEY_APPEARANCE, Appearance.MODERN_GLASS.name()));
        accentOverride = parseHex(PREFS.get(KEY_ACCENT, ""), null);
        boolean defaultDark = appearance == Appearance.MODERN_GLASS
                || appearance == Appearance.MIDNIGHT_BLUE
                || appearance == Appearance.GRAPHITE;
        apply(PREFS.getBoolean(KEY_DARK, defaultDark));
    }

    /** Sets and persists a custom accent color, or {@code null} to use the theme accent. */
    public static void setCustomAccent(Color selected) {
        accentOverride = selected;
        if (selected == null) PREFS.remove(KEY_ACCENT);
        else PREFS.put(KEY_ACCENT, toHex(selected));
        flushPreferences();
        apply(dark);
    }

    /** Removes the custom accent and restores the selected theme accent. */
    public static void clearCustomAccent() {
        setCustomAccent(null);
    }

    /** Switches between light and dark themes. */
    public static void toggle() {
        apply(!dark);
    }

    /** Selects and persists a color palette without changing light/dark mode. */
    public static void setAppearance(Appearance selected) {
        if (selected == null) {
            throw new IllegalArgumentException("selected");
        }
        appearance = selected;
        apply(dark);
    }

    /** Safe to call at startup or later; refreshes all open windows and notifies listeners. */
    public static void apply(boolean useDark) {
        Runnable update = () -> applyOnEventDispatchThread(useDark);
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(update);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while applying the application theme.", ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unable to apply the application theme.", cause);
        }
    }

    private static void applyOnEventDispatchThread(boolean useDark) {
        dark = useDark;
        PREFS.putBoolean(KEY_DARK, dark);
        PREFS.put(KEY_APPEARANCE, appearance.name());
        flushPreferences();

        Color accent = RmsColors.primaryAccent();
        Map<String, String> defaults = new HashMap<>(Map.ofEntries(
                Map.entry("@accentColor", toHex(accent)),
                Map.entry("@background", toHex(RmsColors.background())),
                Map.entry("@foreground", toHex(RmsColors.text())),
                Map.entry("Component.focusColor", toHex(accent)),
                Map.entry("Button.arc", "10"),
                Map.entry("Component.arc", "10"),
                Map.entry("TextComponent.arc", "8"),
                Map.entry("ScrollBar.showButtons", "false"),
                Map.entry("Component.borderColor", toHex(RmsColors.border())),
                Map.entry("Table.background", toHex(RmsColors.surface())),
                Map.entry("Table.foreground", toHex(RmsColors.text())),
                Map.entry("Table.gridColor", toHex(RmsColors.border())),
                Map.entry("Table.selectionBackground", toHex(RmsColors.primaryLight())),
                Map.entry("Table.selectionForeground", toHex(RmsColors.text())),
                Map.entry("Table.alternateRowColor", toHex(RmsColors.background())),
                Map.entry("Table.rowHeight", "32"),
                Map.entry("TableHeader.background", toHex(RmsColors.headerSurface())),
                Map.entry("TableHeader.foreground", toHex(RmsColors.text())),
                Map.entry("TableHeader.height", "36"),
                Map.entry("TabbedPane.tabHeight", "34")));
        if (appearance != Appearance.DEFAULT) {
            defaults.put("List.selectionBackground", toHex(RmsColors.primaryLight()));
            defaults.put("List.selectionForeground", toHex(RmsColors.text()));
        }
        FlatLaf.setGlobalExtraDefaults(defaults);

        if (useDark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }

        UIManager.put("defaultFont", baseFont());
        FlatLaf.updateUI();
        for (Consumer<Boolean> listener : listeners) listener.accept(dark);
        refreshApplicationHierarchy();
    }

    /** Clears and repaints every open window after a theme change, on the EDT. */
    private static void refreshApplicationHierarchy() {
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color parseHex(String value, Color fallback) {
        if (value == null) return fallback;
        String hex = value.trim();
        if (!hex.matches("(?i)^#[0-9A-F]{6}$")) return fallback;
        return Color.decode(hex);
    }

    private static void flushPreferences() {
        try {
            PREFS.flush();
        } catch (BackingStoreException ex) {
            throw new IllegalStateException("Unable to save theme preferences", ex);
        }
    }

    /** Returns the shared base font used by the application interface. */
    private static Font baseFont() {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : new String[] {"Inter", "Segoe UI", "Arial"}) {
            if (available.contains(family)) return new Font(family, Font.PLAIN, 14);
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    }
}