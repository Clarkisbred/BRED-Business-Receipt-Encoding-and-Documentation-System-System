package com.bred.rms.ui.theme;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeManagerTest {
    @Test
    void modernGlassFoundationThemesAndCustomAccentPersist() {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        java.awt.Color previousAccent = ThemeManager.customAccent();
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.MODERN_GLASS);
            ThemeManager.apply(true);
            assertEquals(com.bred.rms.ui.theme.RmsColors.GLASS_BACKGROUND,
                    RmsColors.background());
            assertEquals(com.bred.rms.ui.theme.RmsColors.GLASS_CARD,
                    RmsColors.surface());
            ThemeManager.setCustomAccent(java.awt.Color.decode("#22C55E"));
            assertEquals(java.awt.Color.decode("#22C55E"), RmsColors.primaryAccent());
            ThemeManager.installSaved();
            assertEquals(java.awt.Color.decode("#22C55E"), RmsColors.primaryAccent());
            ThemeManager.setAppearance(ThemeManager.Appearance.CLEAN_LIGHT);
            ThemeManager.apply(false);
            assertEquals(java.awt.Color.decode("#F8FAFC"), RmsColors.background());
        } finally {
            if (previousAccent == null) ThemeManager.clearCustomAccent();
            else ThemeManager.setCustomAccent(previousAccent);
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
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
}
