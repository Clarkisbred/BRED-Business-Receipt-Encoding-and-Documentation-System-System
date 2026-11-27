package com.bred.rms.ui.map;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMercatorTest {
    @Test
    void projectsTheEquatorAndPrimeMeridianToTheWorldCenter() {
        Point2D.Double center = WebMercator.project(0, 0, 0);

        assertEquals(128, center.x, 0.0001);
        assertEquals(128, center.y, 0.0001);
    }

    @Test
    void requestsOnlyTilesIntersectingTheVisibleViewportAndWrapsLongitude() {
        List<WebMercator.Placement> singleTile =
                WebMercator.visibleTiles(new Point2D.Double(128, 128), 0, 256, 256);
        assertEquals(1, singleTile.size());
        assertEquals(new WebMercator.Tile(0, 0, 0), singleTile.get(0).tile());

        List<WebMercator.Placement> acrossWorldEdge =
                WebMercator.visibleTiles(new Point2D.Double(0, 128), 1, 256, 256);
        assertEquals(2, acrossWorldEdge.size());
        assertTrue(acrossWorldEdge.stream().allMatch(tile -> tile.tile().x() >= 0 && tile.tile().x() < 2));
    }

    @Test
    void fitsMultipleCoordinatesAndClampsViewportAtThePoles() {
        List<Point2D.Double> locations = List.of(
                new Point2D.Double(121.0, 14.5),
                new Point2D.Double(121.1, 14.6));
        int zoom = WebMercator.zoomToFit(locations, 500, 360);
        assertTrue(zoom > 2 && zoom <= 18);

        Point2D.Double northPole = WebMercator.project(90, 0, 2);
        List<WebMercator.Placement> visible = WebMercator.visibleTiles(northPole, 2, 256, 256);
        assertTrue(visible.stream().allMatch(tile -> tile.tile().y() == 0));
    }

    @Test
    void returnsNoTilesWhenPannedBeyondTheWorld() {
        assertTrue(WebMercator.visibleTiles(new Point2D.Double(128, -2_000), 0, 256, 256).isEmpty());
    }
}
