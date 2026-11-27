package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatCardTest {
    @Test
    void displaysProfitAndLossTrendsWithArrowsAndThemeColors() {
        StatCard profitCard = new StatCard("BOQ", "₱100", RmsColors::primary);
        profitCard.setTrend(new BigDecimal("-12.50"), false);

        StatCard lossCard = new StatCard("BOQ", "₱100", RmsColors::primary);
        lossCard.setTrend(new BigDecimal("12.50"), false);

        JLabel profitTrend = trendLabel(profitCard);
        JLabel lossTrend = trendLabel(lossCard);

        assertTrue(profitTrend.getText().contains("↑"));
        assertTrue(profitTrend.getText().contains("-12.50"));
        assertEquals(RmsColors.SUCCESS, profitTrend.getForeground());
        assertTrue(lossTrend.getText().contains("↓"));
        assertTrue(lossTrend.getText().contains("12.50"));
        assertEquals(RmsColors.DANGER, lossTrend.getForeground());
    }

    private static JLabel trendLabel(StatCard card) {
        for (Component component : card.getComponents()) {
            if (component instanceof JLabel label
                    && label.getText() != null
                    && (label.getText().contains("↑") || label.getText().contains("↓"))) {
                return label;
            }
        }
        throw new AssertionError("StatCard trend label was not found");
    }
}
