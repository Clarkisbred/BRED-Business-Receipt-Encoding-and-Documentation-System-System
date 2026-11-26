package com.bred.rms.ui;

import com.bred.rms.ai.DuckyPermission;
import com.bred.rms.ai.DuckyPreferences;
import com.bred.rms.ai.GroqCredentialStore;
import com.bred.rms.ai.GroqCredentials;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckySettingsPopupTest {
    @Test
    void consentRequiresAcknowledgementAndSettingsSaveSecureKeyAndAssistMode() {
        String oldModel = DuckyPreferences.model();
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        MemoryCredentialStore store = new MemoryCredentialStore();
        AtomicInteger applied = new AtomicInteger();
        try {
            DuckyPreferences.setCloudConsent(false);
            DuckyPreferences.setPermission(DuckyPermission.ASK);
            GroqCredentials credentials = new GroqCredentials(Map.of(), store);
            DuckySettingsPopup popup = new DuckySettingsPopup(credentials, applied::incrementAndGet);

            assertTrue(popup.saveButton().isEnabled());
            popup.cloudConsentCheckBox().doClick();
            assertFalse(popup.saveButton().isEnabled());
            popup.confirmationCheckBox().doClick();
            assertTrue(popup.saveButton().isEnabled());
            popup.permissionSelector().setSelectedItem(DuckyPermission.ASSIST);
            popup.modelField().setText("test-model");
            popup.apiKeyField().setText("unit-test-key");
            popup.saveButton().doClick();

            assertEquals(1, applied.get());
            assertTrue(DuckyPreferences.cloudConsent());
            assertEquals(DuckyPermission.ASSIST, DuckyPreferences.permission());
            assertEquals("test-model", DuckyPreferences.model());
            assertEquals("unit-test-key", new String(store.read()));
        } finally {
            DuckyPreferences.setModel(oldModel);
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
            store.delete();
        }
    }

    @Test
    void executeModeIsUnavailableAndCancelDoesNotPersistChanges() {
        String oldModel = DuckyPreferences.model();
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        try {
            DuckyPreferences.setCloudConsent(false);
            DuckyPreferences.setPermission(DuckyPermission.ASK);
            DuckySettingsPopup popup = new DuckySettingsPopup(
                    new GroqCredentials(Map.of(), new MemoryCredentialStore()), () -> { });
            popup.permissionSelector().setSelectedItem(DuckyPermission.EXECUTE);
            assertEquals(DuckyPermission.ASK, popup.permissionSelector().getSelectedItem());
            popup.modelField().setText("unsaved-model");
            popup.cancelButton().doClick();

            assertEquals(oldModel, DuckyPreferences.model());
            assertFalse(DuckyPreferences.cloudConsent());
            assertEquals(DuckyPermission.ASK, DuckyPreferences.permission());
        } finally {
            DuckyPreferences.setModel(oldModel);
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
        }
    }

    @Test
    void actionFooterStaysOutsideScrollPaneAndButtonsFitWhenResized() {
        DuckySettingsPopup popup = new DuckySettingsPopup(
                new GroqCredentials(Map.of(), new MemoryCredentialStore()), () -> { });
        try {
            Component scrollComponent = findNamed(popup.getContentPane(), "duckySettingsScroll");
            Component footerComponent = findNamed(popup.getContentPane(), "duckySettingsFooter");
            Component actionsComponent = findNamed(popup.getContentPane(), "duckySettingsActions");
            assertNotNull(scrollComponent);
            assertNotNull(footerComponent);
            assertNotNull(actionsComponent);
            assertSame(popup.getContentPane(), scrollComponent.getParent());
            assertSame(popup.getContentPane(), footerComponent.getParent());
            assertTrue(scrollComponent != footerComponent);

            Container surface = (Container) popup.getContentPane();
            Container scroll = (Container) scrollComponent;
            Container footer = (Container) footerComponent;
            Container actions = (Container) actionsComponent;
            for (int width : new int[] {480, 520, 600}) {
                popup.setSize(width, 440);
                popup.validate();
                layoutTree(surface);

                assertTrue(footer.getX() >= 0);
                assertTrue(footer.getX() + footer.getWidth() <= surface.getWidth());
                assertTrue(footer.getY() + footer.getHeight() <= surface.getHeight());
                assertTrue(scroll.getY() + scroll.getHeight() <= footer.getY());
                for (Component button : actions.getComponents()) {
                    assertTrue(button.getX() >= 0);
                    assertTrue(button.getX() + button.getWidth() <= actions.getWidth());
                    assertTrue(button.getY() >= 0);
                    assertTrue(button.getY() + button.getHeight() <= actions.getHeight());
                }
            }
        } finally {
            popup.dispose();
        }
    }

    private static Component findNamed(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                Component match = findNamed(container, name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) layoutTree(container);
        }
    }

    private static final class MemoryCredentialStore implements GroqCredentialStore {
        private char[] secret;

        @Override public char[] read() { return secret == null ? null : secret.clone(); }

        @Override public void save(char[] apiKey) {
            delete();
            secret = apiKey.clone();
        }

        @Override public void delete() {
            if (secret != null) Arrays.fill(secret, '\0');
            secret = null;
        }
    }
}
