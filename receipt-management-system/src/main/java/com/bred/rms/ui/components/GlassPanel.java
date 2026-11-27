package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** A lightweight Swing-compatible glass surface with layered color and soft border. */
public class GlassPanel extends JPanel {
    private int arc = UiConstants.CARD_ARC;

    public GlassPanel() {
        this(null);
    }

    public GlassPanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                UiConstants.PANEL_PADDING, UiConstants.PANEL_PADDING,
                UiConstants.PANEL_PADDING, UiConstants.PANEL_PADDING));
    }

    public void setArc(int arc) {
        this.arc = Math.max(8, arc);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
        g.setColor(RmsColors.surface());
        g.fill(shape);
        g.setColor(RmsColors.border());
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
        g.dispose();
    }
}
