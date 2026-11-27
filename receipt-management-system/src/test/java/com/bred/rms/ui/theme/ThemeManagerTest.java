package com.bred.rms.ui.theme;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeManagerTest {
    @Test
    void defaultThemeAndCustomAccentPersist() {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        java.awt.Color previousAccent = ThemeManager.customAccent();
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.DEFAULT);
            ThemeManager.apply(false);
            assertEquals(RmsColors.LIGHT_BACKGROUND, RmsColors.background());
            assertEquals(RmsColors.LIGHT_SURFACE, RmsColors.surface());
            assertEquals(RmsColors.PRIMARY, RmsColors.primaryAccent());
            ThemeManager.setCustomAccent(java.awt.Color.decode("#22C55E"));
            assertEquals(java.awt.Color.decode("#22C55E"), RmsColors.primaryAccent());
            ThemeManager.installSaved();
            assertEquals(java.awt.Color.decode("#22C55E"), RmsColors.primaryAccent());
            assertEquals(ThemeManager.Appearance.DEFAULT, ThemeManager.appearance());
        } finally {
            if (previousAccent == null) ThemeManager.clearCustomAccent();
            else ThemeManager.setCustomAccent(previousAccent);
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void removedPresetsAreUnavailableAndSavedSelectionsFallBackToDefault() throws Exception {
        Preferences preferences = Preferences.userNodeForPackage(ThemeManager.class);
        String savedAppearance = preferences.get("theme.appearance", null);
        String savedDark = preferences.get("theme.dark", null);
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            assertEquals(ThemeManager.Appearance.DEFAULT, ThemeManager.Appearance.values()[0]);
            assertFalse(Arrays.stream(ThemeManager.Appearance.values())
                    .map(Enum::name)
                    .anyMatch(List.of("MODERN_GLASS", "MIDNIGHT_BLUE", "CLEAN_LIGHT", "GRAPHITE")::contains));
            preferences.put("theme.appearance", "MODERN_GLASS");
            preferences.remove("theme.dark");

            ThemeManager.installSaved();

            assertEquals(ThemeManager.Appearance.DEFAULT, ThemeManager.appearance());
            assertFalse(ThemeManager.isDark());
            assertEquals("DEFAULT", preferences.get("theme.appearance", null));
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
            if (savedAppearance == null) preferences.remove("theme.appearance");
            else preferences.put("theme.appearance", savedAppearance);
            if (savedDark == null) preferences.remove("theme.dark");
            else preferences.put("theme.dark", savedDark);
            preferences.flush();
        }
    }
    @Test
    void themeChangesAndListenersRunOnTheSwingEventThreadWithoutDuplicateCallbacks() {
        boolean previousDark = ThemeManager.isDark();
        AtomicInteger notifications = new AtomicInteger();
        java.util.function.Consumer<Boolean> listener = ignored -> {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            notifications.incrementAndGet();
        };
        try {
            ThemeManager.addThemeListener(listener);
            ThemeManager.addThemeListener(listener);
            ThemeManager.apply(!previousDark);
            assertEquals(1, notifications.get());
        } finally {
            ThemeManager.removeThemeListener(listener);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void signatureAppearanceKeepsItsColorsReadableInBothModes() {
        ThemeManager.installSaved();
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.BREDUCK_SIGNATURE);
            ThemeManager.installSaved();
            assertEquals(ThemeManager.Appearance.BREDUCK_SIGNATURE, ThemeManager.appearance());

            ThemeManager.apply(false);
            assertEquals(Color.decode("#995900"), RmsColors.primary());
            assertEquals(Color.decode("#F7A72E"), RmsColors.primaryAccent());
            assertEquals(Color.decode("#FCE283"), RmsColors.primaryLight());
            assertEquals(Color.decode("#FFF9E8"), RmsColors.background());
            assertEquals(Color.decode("#FFFDF5"), RmsColors.surface());
            assertEquals(Color.decode("#202124"), RmsColors.primaryForeground());

            ThemeManager.apply(true);
            assertEquals(Color.decode("#F7A72E"), RmsColors.primary());
            assertEquals(Color.decode("#F7A72E"), RmsColors.primaryAccent());
            assertEquals(Color.decode("#3B301C"), RmsColors.primaryLight());
            assertEquals(Color.decode("#202124"), RmsColors.primaryForeground());
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void defaultSelectionHighlightDarkensWithDarkTheme() {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.DEFAULT);
            ThemeManager.apply(false);
            assertEquals(RmsColors.PRIMARY_LIGHT, RmsColors.primaryLight());

            ThemeManager.apply(true);
            assertEquals(Color.decode("#30394B"), RmsColors.primaryLight());
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void dutertesSignatureUsesModeAppropriateGreens() {
        ThemeManager.installSaved();
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.DUTERTES_SIGNATURE);

            ThemeManager.apply(false);
            assertEquals(Color.decode("#A5D6A7"), RmsColors.primaryAccent());
            assertEquals(Color.decode("#F1F8F1"), RmsColors.background());
            assertEquals(Color.decode("#DDF1DE"), RmsColors.primaryLight());
            assertEquals(Color.decode("#173A1A"), RmsColors.primaryForeground());

            ThemeManager.apply(true);
            assertEquals(Color.decode("#2E7D32"), RmsColors.primaryAccent());
            assertEquals(Color.decode("#263B2A"), RmsColors.primaryLight());
            assertEquals(Color.WHITE, RmsColors.primaryForeground());
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void newSignaturesAreAvailableAndUseLightAndDarkBases() {
        ThemeManager.installSaved();
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            ThemeManager.Appearance[] signatures = {
                ThemeManager.Appearance.SHRIMPS_AUCLAIR,
                ThemeManager.Appearance.COSMIC_VIOLET,
                ThemeManager.Appearance.CHILLI_PEPPER_RED,
                ThemeManager.Appearance.MAHOGANY_WOOD,
                ThemeManager.Appearance.MILLIONAIRES_GOLD,
                ThemeManager.Appearance.SILVER_LINING
            };

            for (ThemeManager.Appearance signature : signatures) {
                ThemeManager.setAppearance(signature);
                ThemeManager.apply(false);
                assertEquals(signature, ThemeManager.appearance());
                assertEquals(RmsColors.LIGHT_TEXT, RmsColors.text());

                ThemeManager.apply(true);
                assertEquals(signature, ThemeManager.appearance());
                assertEquals(RmsColors.DARK_BACKGROUND, RmsColors.background());
                assertEquals(RmsColors.DARK_SURFACE, RmsColors.surface());
            }
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }

    @Test
    void reactThemeExportContainsSemanticTokensAndChartPalette() {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.DEFAULT);
            ThemeManager.apply(true);
            ThemeManager.ReactTheme theme = ThemeManager.reactTheme();
            assertEquals("DEFAULT", theme.appearance());
            assertTrue(theme.dark());
            assertEquals("#15171C", theme.background());
            assertEquals("#20232B", theme.sidebar());
            assertEquals("#1F5FBF", theme.chart1());
            assertEquals("#218838", theme.chart2());
            assertTrue(theme.surface().startsWith("#"));
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
        }
    }
}
