package com.bred.rms.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactDesktopPanelTest {
    @Test
    void browserProfilesAreIsolatedBetweenApplicationProcesses() {
        Path installRoot = Path.of("bred", "jcef");

        assertNotEquals(
                ReactDesktopPanel.profilePath(installRoot, 1001),
                ReactDesktopPanel.profilePath(installRoot, 1002));
    }

    @Test
    void onlyTrustedMainFrameFailuresCanReplaceTheReactWorkspace() {
        String origin = "http://127.0.0.1:51321";

        assertTrue(ReactDesktopPanel.isTrustedMainFrameUrl(true, origin + "/", origin));
        assertFalse(ReactDesktopPanel.isTrustedMainFrameUrl(
                false, "https://www.openstreetmap.org/export/embed.html", origin));
        assertFalse(ReactDesktopPanel.isTrustedMainFrameUrl(
                true, "https://www.openstreetmap.org/export/embed.html", origin));
        assertFalse(ReactDesktopPanel.isTrustedMainFrameUrl(
                true, origin + "/untrusted", origin));
    }
}
