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
    private static boolean dark;
    private static Appearance appearance = Appearance.DEFAULT;
    private static final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    /** Available application color palettes. */
    public enum Appearance {
        DEFAULT("Default"),
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
        appearance = Appearance.fromPreference(PREFS.get(KEY_APPEARANCE, Appearance.DEFAULT.name()));
        apply(PREFS.getBoolean(KEY_DARK, false)); 
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
                Map.entry("TextComponent.arc", "10"),
                Map.entry("ScrollBar.showButtons", "false"),
                Map.entry("Table.rowHeight", "28"),
                Map.entry("TableHeader.height", "32"),
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
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
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