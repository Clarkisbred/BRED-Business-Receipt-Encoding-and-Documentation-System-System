package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.ui.theme.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.Color;
import java.awt.Font;

/** Shared clean, minimal table styling for every BRED panel and dialog. */
public final class ModernTable {
    private ModernTable() {}

    public static void apply(JTable table) {
        table.setRowHeight(34);
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 1));
        table.setGridColor(RmsColors.border());
        table.setSelectionBackground(RmsColors.primaryLight());
        table.setSelectionForeground(RmsColors.text());
        table.setOpaque(true);
        table.setBackground(RmsColors.surface());
        table.setForeground(RmsColors.text());

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setResizingAllowed(true);
        header.setPreferredSize(new java.awt.Dimension(0, 38));
        header.setOpaque(true);
        header.setBackground(RmsColors.headerSurface());
        header.setForeground(RmsColors.text());
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, RmsColors.border()));

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(
                    JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                java.awt.Component c = super.getTableCellRendererComponent(
                        t, value, selected, focus, row, column);
                c.setForeground(selected ? RmsColors.text() : RmsColors.text());
                c.setBackground(selected ? RmsColors.primaryLight()
                        : (row % 2 == 0 ? RmsColors.surface()
                        : alternateSurface()));
                setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
                return c;
            }
        };
        renderer.setOpaque(true);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Number.class, renderer);
    }

    private static Color alternateSurface() {
        Color surface = RmsColors.surface();
        if (!ThemeManager.isDark()) return surface.darker().brighter();
        return surface.brighter();
    }
}
