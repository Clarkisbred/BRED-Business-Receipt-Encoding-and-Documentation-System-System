package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** Rounded, theme-aware primary action button. */
public final class ModernButton extends JButton {
    public ModernButton(String text) {
        super(text);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setForeground(RmsColors.primaryForeground());
        setMargin(new java.awt.Insets(8, 14, 8, 14));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setForeground(RmsColors.primaryForeground());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                1, 1, getWidth() - 3, getHeight() - 3,
                UiConstants.BUTTON_ARC, UiConstants.BUTTON_ARC);
        g.setColor(isEnabled() ? RmsColors.primaryAccent() : RmsColors.border());
        g.fill(shape);
        g.setStroke(new BasicStroke(1f));
        g.setColor(RmsColors.primaryAccent().darker());
        g.draw(shape);
        g.dispose();
        super.paintComponent(graphics);
    }
}
