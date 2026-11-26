package com.bred.rms.ui;

import com.bred.rms.config.AppConfig;
import com.bred.rms.config.AppVersion;
import com.bred.rms.models.Role;
import com.bred.rms.security.Module;
import com.bred.rms.ui.auth.LoginPanel;
import com.bred.rms.ui.auth.SetupPanel;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.receipts.ReceiptDirectoryPanel;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JToggleButton;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellSmokeTest {
    @Test
    void loginAndSetupPanelsBuildHeadless() throws Exception {
        LoginPanel login = new LoginPanel((email, password, done) -> { });
        assertNotNull(login);
        assertEquals("Version: " + AppVersion.current(), fieldLabel(login, "releaseVersionLabel").getText());
        assertNotNull(new SetupPanel());
    }

    @Test
    void shellForEncoderOffersNoUsersOrActivityLog() {
        var ctx = TestContexts.withUser(Role.ENCODER);
        Shell shell = new Shell(ctx, () -> { });
        assertFalse(shell.visibleModules().contains(Module.USERS));
        assertFalse(shell.visibleModules().contains(Module.ACTIVITY_LOG));
        assertTrue(shell.visibleModules().contains(Module.RECEIPTS));
        assertDoesNotThrow(() -> shell.show(Module.SETTINGS));
        assertDoesNotThrow(() -> shell.show(Module.REPORTS));
        assertDoesNotThrow(() -> shell.show(Module.DASHBOARD));
    }

    @Test
    void shellForAdminOffersEverything() {
        Shell shell = new Shell(TestContexts.withUser(Role.ADMIN), () -> { });
        assertFalse(shell.visibleModules().contains(Module.COMPANIES));
        assertFalse(shell.visibleModules().contains(Module.HISTORY));
        assertTrue(shell.visibleModules().contains(Module.USERS));
    }

    @Test
    void documentImportEntryPointIsVisibleOnlyToAdministrators() throws Exception {
        ReceiptDirectoryPanel encoder = new ReceiptDirectoryPanel(TestContexts.withUser(Role.ENCODER),
                ignored -> { }, () -> { }, () -> { });
        ReceiptDirectoryPanel admin = new ReceiptDirectoryPanel(TestContexts.withUser(Role.ADMIN),
                ignored -> { }, () -> { }, () -> { });

        assertFalse(directoryImportButton(encoder).isVisible());
        assertTrue(directoryImportButton(admin).isVisible());
    }

    @Test
    void shellForCreatorOffersGlobalCompaniesAndHistory() throws Exception {
        Shell shell = new Shell(TestContexts.withUser(Role.CREATOR), () -> { });
        assertTrue(shell.visibleModules().contains(Module.USERS));
        assertTrue(shell.visibleModules().contains(Module.COMPANIES));
        assertTrue(shell.visibleModules().contains(Module.HISTORY));

        JPanel sidebar = (JPanel) shell.getComponent(0);
        JScrollPane navigation = java.util.Arrays.stream(sidebar.getComponents())
                .filter(JScrollPane.class::isInstance)
                .map(JScrollPane.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, navigation.getVerticalScrollBarPolicy());
        assertEquals(12, navigation.getVerticalScrollBar().getPreferredSize().width);

        JToggleButton companies = navigationButton(shell, Module.COMPANIES);
        assertTrue(companies.isEnabled());
        SwingUtilities.invokeAndWait(companies::doClick);
        assertTrue(companies.isSelected());
        assertEquals(Module.COMPANIES.title(), shellTitle(shell).getText());

        JToggleButton history = navigationButton(shell, Module.HISTORY);
        SwingUtilities.invokeAndWait(history::doClick);
        assertTrue(history.isSelected());
        assertEquals(Module.HISTORY.title(), shellTitle(shell).getText());
    }

    @Test
    void settingsOffersTheSignatureAppearance() throws Exception {
        SettingsPanel settings = new SettingsPanel(TestContexts.withUser(Role.ENCODER));
        JComboBox<?> appearances = java.util.Arrays.stream(settings.getComponents())
                .filter(JComboBox.class::isInstance)
                .map(JComboBox.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(ThemeManager.Appearance.values().length, appearances.getItemCount());
        assertEquals(ThemeManager.Appearance.BREDUCK_SIGNATURE, appearances.getItemAt(1));
        assertEquals(appearances.getPreferredSize(), appearances.getMaximumSize());
        assertEquals("Installed version: " + AppVersion.current(),
                fieldLabel(settings, "releaseVersion").getText());
    }

    @Test
    void styledScrollbarsKeepTheirCustomUiAfterThemeUpdates() throws Exception {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        try {
            StyledScrollPane scroll = new StyledScrollPane(new JPanel());
            Class<?> verticalUi = scroll.getVerticalScrollBar().getUI().getClass();
            Class<?> horizontalUi = scroll.getHorizontalScrollBar().getUI().getClass();

            ThemeManager.setAppearance(ThemeManager.Appearance.SILVER_LINING);
            ThemeManager.apply(!previousDark);
            SwingUtilities.invokeAndWait(scroll::updateUI);

            assertEquals(verticalUi, scroll.getVerticalScrollBar().getUI().getClass());
            assertEquals(horizontalUi, scroll.getHorizontalScrollBar().getUI().getClass());
            assertEquals(12, scroll.getVerticalScrollBar().getPreferredSize().width);
            assertEquals(12, scroll.getHorizontalScrollBar().getPreferredSize().height);
        } finally {
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    @Test
    void settingsAndLoginTextStayReadableWithSilverLining() throws Exception {
        ThemeManager.Appearance previousAppearance = ThemeManager.appearance();
        boolean previousDark = ThemeManager.isDark();
        SettingsPanel settings = null;
        LoginPanel login = null;
        try {
            ThemeManager.setAppearance(ThemeManager.Appearance.SILVER_LINING);
            ThemeManager.apply(true);
            settings = new SettingsPanel(TestContexts.withUser(Role.ENCODER));
            login = new LoginPanel((email, password, done) -> { });

            ThemeManager.apply(false);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(RmsColors.primaryForeground(), settingsSubmitButton(settings).getForeground());
            assertEquals(Color.WHITE, findLabel(login, "BRED").getForeground());
            assertEquals(RmsColors.primaryForeground(), loginSignInButton(login).getForeground());

            ThemeManager.apply(true);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(RmsColors.primaryForeground(), settingsSubmitButton(settings).getForeground());
            assertEquals(RmsColors.LIGHT_TEXT, findLabel(login, "BRED").getForeground());
            assertEquals(RmsColors.primaryForeground(), loginSignInButton(login).getForeground());
        } finally {
            if (login != null) login.removeNotify();
            if (settings != null) settings.removeNotify();
            ThemeManager.setAppearance(previousAppearance);
            ThemeManager.apply(previousDark);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static JLabel findLabel(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return label;
            if (component instanceof Container child) {
                JLabel match = findLabel(child, text);
                if (match != null) return match;
            }
        }
        throw new AssertionError("Label not found: " + text);
    }

    private static JButton settingsSubmitButton(SettingsPanel settings) throws ReflectiveOperationException {
        var field = SettingsPanel.class.getDeclaredField("submitReport");
        field.setAccessible(true);
        return (JButton) field.get(settings);
    }

    private static JLabel fieldLabel(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (JLabel) field.get(owner);
    }

    private static JButton loginSignInButton(LoginPanel login) throws ReflectiveOperationException {
        var field = LoginPanel.class.getDeclaredField("signInButton");
        field.setAccessible(true);
        return (JButton) field.get(login);
    }

    private static JToggleButton navigationButton(Shell shell, Module module) throws ReflectiveOperationException {
        var field = Shell.class.getDeclaredField("navButtons");
        field.setAccessible(true);
        return (JToggleButton) ((Map<?, ?>) field.get(shell)).get(module);
    }

    private static JLabel shellTitle(Shell shell) throws ReflectiveOperationException {
        var field = Shell.class.getDeclaredField("title");
        field.setAccessible(true);
        return (JLabel) field.get(shell);
    }

    private static JButton directoryImportButton(ReceiptDirectoryPanel panel) throws ReflectiveOperationException {
        var field = ReceiptDirectoryPanel.class.getDeclaredField("importDocuments");
        field.setAccessible(true);
        return (JButton) field.get(panel);
    }
}

