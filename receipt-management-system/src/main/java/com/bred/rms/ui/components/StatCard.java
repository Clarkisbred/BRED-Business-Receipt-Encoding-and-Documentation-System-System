package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

/** Summary card: small title, large value, colored accent edge. */
public final class StatCard extends JPanel {
    private final Supplier<Color> accent;
    private JLabel valueLabel;
    private JLabel titleLabel;
    private JLabel trendLabel;

    /** Creates a stat card instance. */
    public StatCard(String title, String value, Color accent) {
        this(title, value, () -> accent);
    }

    /** Creates a stat card whose accent can follow the active appearance. */
    public StatCard(String title, String value, Supplier<Color> accent) {
        this.accent = accent;
        setLayout(new BorderLayout(0, 4));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 16));
        titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 12f));
        valueLabel = new JLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 26f));
        trendLabel = new JLabel(" ");
        trendLabel.setFont(trendLabel.getFont().deriveFont(Font.BOLD, 11f));
        add(titleLabel, BorderLayout.NORTH);
        add(valueLabel, BorderLayout.CENTER);
        add(trendLabel, BorderLayout.SOUTH);
        updateUI();
    }

    /** Updates the summary value displayed by this card. */
    public void setValue(String value) { valueLabel.setText(value); }

    /** Sets a green profit or red loss trend; neutral values are theme-muted. */
    public void setTrend(java.math.BigDecimal percent, boolean positiveIsProfit) {
        if (percent == null) {
            trendLabel.setText(" ");
            return;
        }
        int comparison = percent.compareTo(java.math.BigDecimal.ZERO);
        if (comparison == 0) {
            trendLabel.setText("0.00%  —");
            trendLabel.setForeground(RmsColors.SECONDARY_TEXT);
            return;
        }
        boolean profit = positiveIsProfit ? comparison > 0 : comparison < 0;
        String arrow = profit ? "↑" : "↓";
        trendLabel.setText(arrow + " " + percent.setScale(2,
                java.math.RoundingMode.HALF_UP).toPlainString() + "%");
        trendLabel.setForeground(profit ? RmsColors.SUCCESS : RmsColors.DANGER);
    }

    /** Updates ui. */
    @Override public void updateUI() {
        super.updateUI();
        if (titleLabel != null) titleLabel.setForeground(RmsColors.SECONDARY_TEXT);
        if (valueLabel != null) valueLabel.setForeground(RmsColors.text());
    }

    /** Paints component. */
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D card = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.setColor(RmsColors.surface());
        g2.fill(card);
        g2.setColor(accent.get());
        g2.fillRoundRect(1, 10, 4, getHeight() - 20, 4, 4);
        g2.setColor(RmsColors.border());
        g2.setStroke(new BasicStroke(1f));
        g2.draw(card);
        g2.dispose();
    }
}
