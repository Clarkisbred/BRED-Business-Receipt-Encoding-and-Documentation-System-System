package com.bred.rms.ui.map;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Web Mercator projection and visible-tile calculations for the project map. */
final class WebMercator {
    static final int TILE_SIZE = 256;
    private static final double MAX_LATITUDE = 85.05112878;
    private static final int MIN_ZOOM = 2;
    private static final int MAX_ZOOM = 18;

    record Tile(int zoom, int x, int y) { }
    record Placement(Tile tile, int screenX, int screenY) { }

    private WebMercator() { }

    static Point2D.Double project(double latitude, double longitude, int zoom) {
        double latitudeClamped = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
        double worldSize = TILE_SIZE * Math.scalb(1.0, zoom);
        double x = (longitude + 180.0) / 360.0 * worldSize;
        double sinLatitude = Math.sin(Math.toRadians(latitudeClamped));
        double y = (0.5 - Math.log((1 + sinLatitude) / (1 - sinLatitude)) / (4 * Math.PI)) * worldSize;
        return new Point2D.Double(x, y);
    }

    static int zoomToFit(Collection<Point2D.Double> points, int width, int height) {
        if (points.isEmpty()) return 3;
        if (points.stream().allMatch(point -> point.distance(points.iterator().next()) < 0.01)) {
            return 14;
        }
        for (int zoom = MAX_ZOOM; zoom >= MIN_ZOOM; zoom--) {
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Point2D.Double point : points) {
                Point2D.Double projected = project(point.y, point.x, zoom);
                minX = Math.min(minX, projected.x);
                maxX = Math.max(maxX, projected.x);
                minY = Math.min(minY, projected.y);
                maxY = Math.max(maxY, projected.y);
            }
            if (maxX - minX <= Math.max(1, width - 120)
                    && maxY - minY <= Math.max(1, height - 120)) return zoom;
        }
        return MIN_ZOOM;
    }

    static Point2D.Double center(Collection<Point2D.Double> points, int zoom) {
        if (points.isEmpty()) return project(0, 0, zoom);
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point2D.Double point : points) {
            Point2D.Double projected = project(point.y, point.x, zoom);
            minX = Math.min(minX, projected.x);
            maxX = Math.max(maxX, projected.x);
            minY = Math.min(minY, projected.y);
            maxY = Math.max(maxY, projected.y);
        }
        return new Point2D.Double((minX + maxX) / 2, (minY + maxY) / 2);
    }

    static List<Placement> visibleTiles(Point2D.Double center, int zoom, int width, int height) {
        if (width <= 0 || height <= 0) return List.of();
        if (zoom < 0 || zoom > 22) throw new IllegalArgumentException("Zoom must be between 0 and 22.");
        int tileCount = 1 << zoom;
        double left = center.x - width / 2.0;
        double top = center.y - height / 2.0;
        int firstX = (int) Math.floor(left / TILE_SIZE);
        int lastX = (int) Math.floor((left + width - 1) / TILE_SIZE);
        int rawFirstY = (int) Math.floor(top / TILE_SIZE);
        int rawLastY = (int) Math.floor((top + height - 1) / TILE_SIZE);
        if (rawLastY < 0 || rawFirstY >= tileCount) return List.of();
        int firstY = Math.max(0, rawFirstY);
        int lastY = Math.min(tileCount - 1, rawLastY);
        List<Placement> visible = new ArrayList<>((lastX - firstX + 1) * (lastY - firstY + 1));
        for (int tileY = firstY; tileY <= lastY; tileY++) {
            for (int tileX = firstX; tileX <= lastX; tileX++) {
                int wrappedX = Math.floorMod(tileX, tileCount);
                visible.add(new Placement(new Tile(zoom, wrappedX, tileY),
                        tileX * TILE_SIZE - (int) Math.floor(left),
                        tileY * TILE_SIZE - (int) Math.floor(top)));
            }
        }
        return visible;
    }
}
