package com.bred.rms.ui.components;

import com.bred.rms.ui.theme.RmsColors;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Set;
import java.util.function.Supplier;

/** Small, theme-aware Lucide-style line icons drawn as scalable Java2D vectors. */
public final class LucideIcon implements Icon {
    private static final Set<String> NAMES = Set.of(
            "activity", "building-2", "chart-column", "chevron-down", "folder-kanban",
            "hard-hat", "history", "layout-dashboard", "log-out", "map-pin", "message-square",
            "moon", "pencil", "plus", "receipt", "refresh-cw", "search", "settings",
            "sparkles", "sun", "trash-2", "truck", "user-round", "users", "bell", "filter"
    );

    private final String name;
    private final int size;
    private final Supplier<Color> color;

    public LucideIcon(String name, int size) {
        this(name, size, RmsColors::text);
    }

    public LucideIcon(String name, int size, Supplier<Color> color) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unsupported Lucide icon: " + name);
        if (size < 1) throw new IllegalArgumentException("Icon size must be positive.");
        if (color == null) throw new IllegalArgumentException("Icon color supplier is required.");
        this.name = name;
        this.size = size;
        this.color = color;
    }

    public static boolean supports(String name) {
        return NAMES.contains(name);
    }

    public static Set<String> supportedNames() {
        return NAMES;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.scale(size / 24d, size / 24d);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(color.get());
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        draw(g);
        g.dispose();
    }

    private void draw(Graphics2D g) {
        switch (name) {
            case "layout-dashboard" -> {
                roundRect(g, 3, 3, 8, 8, 1);
                roundRect(g, 14, 3, 7, 5, 1);
                roundRect(g, 14, 11, 7, 10, 1);
                roundRect(g, 3, 14, 8, 7, 1);
            }
            case "message-square" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(21, 15); p.curveTo(21, 17.2, 19.2, 19, 17, 19);
                p.lineTo(7, 19); p.lineTo(3, 22); p.lineTo(3, 7);
                p.curveTo(3, 4.8, 4.8, 3, 7, 3); p.lineTo(17, 3);
                p.curveTo(19.2, 3, 21, 4.8, 21, 7); p.closePath();
                g.draw(p);
            }
            case "sparkles" -> {
                polyline(g, 12, 3, 13.8, 10.2, 21, 12, 13.8, 13.8, 12, 21,
                        10.2, 13.8, 3, 12, 10.2, 10.2, 12, 3);
                line(g, 19, 3, 19, 7); line(g, 17, 5, 21, 5);
                line(g, 5, 16, 5, 21); line(g, 2.5, 18.5, 7.5, 18.5);
            }
            case "receipt" -> {
                polyline(g, 5, 2, 19, 2, 19, 22, 15.5, 20, 12, 22, 8.5, 20, 5, 22, 5, 2);
                line(g, 9, 7, 15, 7); line(g, 9, 11, 15, 11); line(g, 9, 15, 13, 15);
            }
            case "truck" -> {
                roundRect(g, 2, 5, 13, 12, 1);
                polyline(g, 15, 9, 19, 9, 22, 13, 22, 17, 15, 17, 15, 9);
                circle(g, 7, 18, 2); circle(g, 18, 18, 2);
            }
            case "folder-kanban" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 6); p.lineTo(3, 19); p.quadTo(3, 21, 5, 21);
                p.lineTo(19, 21); p.quadTo(21, 21, 21, 19); p.lineTo(21, 8);
                p.quadTo(21, 6, 19, 6); p.lineTo(12, 6); p.lineTo(10, 3);
                p.lineTo(5, 3); p.quadTo(3, 3, 3, 5); p.closePath();
                g.draw(p);
                line(g, 9, 10, 9, 17); line(g, 14, 10, 14, 15); line(g, 18, 10, 18, 18);
            }
            case "map-pin" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(20, 10); p.curveTo(20, 15, 12, 22, 12, 22);
                p.curveTo(12, 22, 4, 15, 4, 10); p.curveTo(4, 5.6, 7.6, 2, 12, 2);
                p.curveTo(16.4, 2, 20, 5.6, 20, 10); p.closePath();
                g.draw(p); circle(g, 12, 10, 2.5);
            }
            case "hard-hat" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 17); p.lineTo(4, 12); p.curveTo(5, 7, 8, 4, 12, 4);
                p.curveTo(16, 4, 19, 7, 20, 12); p.lineTo(21, 17); p.closePath();
                g.draw(p); line(g, 2, 17, 22, 17); line(g, 12, 4, 12, 17);
                line(g, 6, 10, 18, 10);
            }
            case "chart-column" -> {
                line(g, 3, 3, 3, 21); line(g, 3, 21, 22, 21);
                roundRect(g, 6, 12, 4, 6, 1); roundRect(g, 13, 7, 4, 11, 1);
                roundRect(g, 20, 3, 2, 15, 1);
            }
            case "users" -> {
                circle(g, 9, 8, 3); circle(g, 18, 9, 2.5);
                Path2D left = new Path2D.Double();
                left.moveTo(3, 20); left.curveTo(3, 16, 5.5, 14, 9, 14);
                left.curveTo(12.5, 14, 15, 16, 15, 20); g.draw(left);
                Path2D right = new Path2D.Double();
                right.moveTo(16, 15); right.curveTo(19.5, 14.5, 22, 16.5, 22, 20); g.draw(right);
            }
            case "activity" -> polyline(g, 3, 12, 7, 12, 10, 4, 14, 20, 17, 12, 21, 12);
            case "building-2" -> {
                roundRect(g, 3, 3, 18, 18, 1);
                line(g, 9, 3, 9, 21); line(g, 15, 3, 15, 21);
                line(g, 3, 9, 21, 9); line(g, 3, 15, 21, 15);
                line(g, 6, 6, 6, 6); line(g, 12, 6, 12, 6); line(g, 18, 6, 18, 6);
                line(g, 6, 12, 6, 12); line(g, 12, 12, 12, 12); line(g, 18, 12, 18, 12);
            }
            case "history" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 8); p.curveTo(4.5, 4.5, 8, 2, 12, 2);
                p.curveTo(17.5, 2, 22, 6.5, 22, 12);
                p.curveTo(22, 17.5, 17.5, 22, 12, 22); p.curveTo(8, 22, 4.5, 19.5, 3, 16);
                g.draw(p); polyline(g, 3, 3, 3, 8, 8, 8);
                line(g, 12, 6, 12, 12); line(g, 12, 12, 16, 14);
            }
            case "settings" -> {
                circle(g, 12, 12, 3);
                for (int i = 0; i < 8; i++) {
                    Graphics2D tooth = (Graphics2D) g.create();
                    tooth.rotate(i * Math.PI / 4, 12, 12);
                    roundRect(tooth, 10, 2, 4, 4, 1);
                    tooth.dispose();
                }
                circle(g, 12, 12, 8);
            }
            case "search" -> {
                circle(g, 10.5, 10.5, 7); line(g, 16, 16, 22, 22);
            }
            case "bell" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(18, 9); p.curveTo(18, 5, 15.5, 3, 12, 3);
                p.curveTo(8.5, 3, 6, 5, 6, 9); p.curveTo(6, 15, 3, 16, 3, 18);
                p.lineTo(21, 18); p.curveTo(21, 16, 18, 15, 18, 9); g.draw(p);
                Path2D clapper = new Path2D.Double();
                clapper.moveTo(10, 21); clapper.quadTo(12, 23, 14, 21); g.draw(clapper);
            }
            case "chevron-down" -> polyline(g, 6, 9, 12, 15, 18, 9);
            case "sun" -> {
                circle(g, 12, 12, 4);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    line(g, 12 + Math.cos(angle) * 7, 12 + Math.sin(angle) * 7,
                            12 + Math.cos(angle) * 9, 12 + Math.sin(angle) * 9);
                }
            }
            case "moon" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(20, 15); p.curveTo(18, 19, 14, 21, 10, 20);
                p.curveTo(4, 19, 2, 12, 5, 7); p.curveTo(7, 4, 10, 3, 13, 3);
                p.curveTo(10, 6, 10, 11, 13, 14); p.curveTo(15, 16, 18, 16, 20, 15);
                g.draw(p);
            }
            case "log-out" -> {
                roundRect(g, 3, 3, 12, 18, 1);
                line(g, 10, 12, 22, 12); polyline(g, 18, 8, 22, 12, 18, 16);
            }
            case "user-round" -> {
                circle(g, 12, 8, 4);
                Path2D p = new Path2D.Double();
                p.moveTo(4, 21); p.curveTo(4, 16.5, 7, 14, 12, 14);
                p.curveTo(17, 14, 20, 16.5, 20, 21); g.draw(p);
            }
            case "plus" -> { line(g, 12, 5, 12, 19); line(g, 5, 12, 19, 12); }
            case "pencil" -> {
                polyline(g, 12, 20, 21, 11, 13, 3, 4, 12, 3, 21, 12, 20);
                line(g, 11, 5, 19, 13); line(g, 3, 21, 10, 19);
            }
            case "trash-2" -> {
                line(g, 3, 6, 21, 6); line(g, 8, 6, 8, 4); line(g, 8, 4, 16, 4); line(g, 16, 4, 16, 6);
                polyline(g, 5, 6, 6, 21, 18, 21, 19, 6);
                line(g, 10, 10, 10, 17); line(g, 14, 10, 14, 17);
            }
            case "refresh-cw" -> {
                Arc2D first = new Arc2D.Double(4, 4, 16, 16, 45, 205, Arc2D.OPEN);
                Arc2D second = new Arc2D.Double(4, 4, 16, 16, 225, 205, Arc2D.OPEN);
                g.draw(first); g.draw(second);
                polyline(g, 20, 4, 20, 9, 15, 9); polyline(g, 4, 20, 4, 15, 9, 15);
            }
            case "filter" -> {
                polyline(g, 3, 4, 21, 4, 14, 12, 14, 19, 10, 21, 10, 12, 3, 4);
            }
            default -> throw new IllegalStateException("Unsupported Lucide icon: " + name);
        }
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
    }

    private static void circle(Graphics2D g, double centerX, double centerY, double radius) {
        g.draw(new Ellipse2D.Double(centerX - radius, centerY - radius, radius * 2, radius * 2));
    }

    private static void roundRect(Graphics2D g, double x, double y, double width, double height, double radius) {
        g.draw(new RoundRectangle2D.Double(x, y, width, height, radius * 2, radius * 2));
    }

    private static void polyline(Graphics2D g, double... points) {
        Path2D path = new Path2D.Double();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        g.draw(path);
    }
}
