package com.bred.rms.ui;

import com.bred.rms.models.Role;
import com.bred.rms.ui.construction.ConstructionPanel;
import org.junit.jupiter.api.Test;

import javax.swing.JTabbedPane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionPanelTest {
    @Test
    void constructionPanelIncludesTheCompleteRoadmapWorkflowTabs() {
        ConstructionPanel panel = new ConstructionPanel(TestContexts.withUser(Role.ADMIN));
        JTabbedPane tabs = findTabbedPane(panel);
        assertTrue(tabs != null);
        assertEquals("Licenses", tabs.getTitleAt(0));
        assertTrue(tabs.getTabCount() >= 30);
        assertTrue(hasTab(tabs, "CWT received"));
        assertTrue(hasTab(tabs, "Cost estimates"));
        assertTrue(hasTab(tabs, "Contracts"));
        assertTrue(hasTab(tabs, "BOQ items"));
        assertTrue(hasTab(tabs, "Variation orders"));
        assertTrue(hasTab(tabs, "Accomplishments"));
        assertTrue(hasTab(tabs, "Milestones"));
        assertTrue(hasTab(tabs, "Schedule snapshots"));
        assertTrue(hasTab(tabs, "Billings"));
        assertTrue(hasTab(tabs, "Billing deductions"));
        assertTrue(hasTab(tabs, "Collections"));
        assertTrue(hasTab(tabs, "Project expenses"));
        assertTrue(hasTab(tabs, "Payables"));
        assertTrue(hasTab(tabs, "Payments"));
        assertTrue(hasTab(tabs, "P&L snapshots"));
        assertTrue(hasTab(tabs, "Closeout"));
        assertTrue(hasTab(tabs, "Offline queue"));
    }

    private static JTabbedPane findTabbedPane(java.awt.Container root) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JTabbedPane pane) return pane;
            if (component instanceof java.awt.Container container) {
                JTabbedPane found = findTabbedPane(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasTab(JTabbedPane tabs, String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equals(tabs.getTitleAt(i))) return true;
        }
        return false;
    }
}
