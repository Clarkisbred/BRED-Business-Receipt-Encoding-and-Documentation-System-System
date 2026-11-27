package com.bred.rms.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReactDesktopPanelTest {
    @Test
    void browserProfilesAreIsolatedBetweenApplicationProcesses() {
        Path installRoot = Path.of("bred", "jcef");

        assertNotEquals(
                ReactDesktopPanel.profilePath(installRoot, 1001),
                ReactDesktopPanel.profilePath(installRoot, 1002));
    }
}
