package com.bred.rms.ui;

import com.bred.rms.models.Role;
import com.bred.rms.ui.map.LiveProjectMapPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LiveProjectMapPanelTest {
    @Test
    void mapWorkspaceBuildsAndCanRefreshHeadless() {
        LiveProjectMapPanel panel =
                new LiveProjectMapPanel(TestContexts.withUser(Role.ADMIN));
        assertNotNull(panel);
        assertDoesNotThrow(panel::refreshData);
    }
}
