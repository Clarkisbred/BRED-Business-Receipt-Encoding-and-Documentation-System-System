package com.bred.rms.ui;

import com.bred.rms.models.Role;
import com.bred.rms.ui.construction.ConstructionPanel;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionPanelTest {
    @Test
    void constructionPanelIncludesTheCompleteRoadmapWorkflowTabs() {
        ConstructionPanel panel = new ConstructionPanel(TestContexts.withUser(Role.ADMIN));
        assertTrue(hasLabel(panel, "Construction Management"));
        assertTrue(hasButton(panel, "Licenses"));
        assertTrue(hasButton(panel, "Cost estimates"));
        assertTrue(hasButton(panel, "Contracts"));
        assertTrue(hasButton(panel, "BOQ items"));
        assertTrue(hasButton(panel, "Billings"));
        assertTrue(hasButton(panel, "Royalties"));
        assertTrue(hasButton(panel, "Project expenses"));
        assertTrue(hasButton(panel, "Purchase orders"));
        assertTrue(hasButton(panel, "Workers"));
        assertTrue(hasButton(panel, "Equipment usage"));
        assertTrue(hasButton(panel, "Project risks"));
        assertTrue(hasButton(panel, "Quality NCR"));
        assertTrue(hasButton(panel, "Closeout"));
        assertTrue(hasButton(panel, "Offline queue"));

        assertTrue(ConstructionPanel.workspaceCategories().stream()
                .filter(category -> "Billing and Finance".equals(category.title()))
                .flatMap(category -> category.tabs().stream())
                .anyMatch(tab -> "Royalties".equals(tab.title()) && "royalty_records".equals(tab.table())));
        assertTrue(ConstructionPanel.workspaceCategories().stream()
                .noneMatch(category -> "Royalties".equals(category.title())));
    }

    private static boolean hasLabel(java.awt.Container root, String text) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return true;
            if (component instanceof java.awt.Container container && hasLabel(container, text)) return true;
        }
        return false;
    }

    private static boolean hasButton(java.awt.Container root, String text) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return true;
            if (component instanceof java.awt.Container container && hasButton(container, text)) return true;
        }
        return false;
    }
}
