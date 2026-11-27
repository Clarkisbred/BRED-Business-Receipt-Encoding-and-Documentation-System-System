package com.bred.rms.ui.map;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Project;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.ui.components.SectionHeader;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.ModernTable;
import com.bred.rms.ui.components.LucideIcon;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.math.BigDecimal;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

/** Verified-coordinate project map workspace backed by project_map_markers. */
public final class LiveProjectMapPanel extends JPanel implements RefreshablePanel {
    private record Marker(UUID id, UUID projectId, String name, BigDecimal latitude,
                          BigDecimal longitude, String type, String status,
                          String description, String lastSeen) {}

    private record Loaded(List<Project> projects, List<Marker> markers) {}
    private record Choice(UUID id, String label) {
        @Override public String toString() { return label; }
    }

    private final AppContext ctx;
    private final JTextField search = new JTextField(20);
    private final JComboBox<String> statusFilter =
            new JComboBox<>(new String[]{"All", "Active", "Delayed", "At Risk", "Stopped", "Completed"});
    private final JLabel status = new JLabel(" ");
    private final DefaultTableModel model =
            new DefaultTableModel(new String[]{"Project", "Marker", "Status", "Latitude", "Longitude"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
    private final JTable table = new JTable(model);
    private final MapPanel map = new MapPanel();
    private final JTextField locationSearch = new JTextField(24);
    private final JComboBox<Choice> projectChoice = new JComboBox<>();
    private final JComboBox<NominatimGeocoder.Result> locationResults = new JComboBox<>();
    private final JButton searchLocation = new JButton("Search location");
    private final JButton addLocation = new JButton("Add location");
    private final NominatimGeocoder geocoder = new NominatimGeocoder();
    private List<Marker> shown = new ArrayList<>();

    public LiveProjectMapPanel(AppContext ctx) {
        super(new BorderLayout(12, 12));
        this.ctx = ctx;
        setOpaque(false);

        SectionHeader header = new SectionHeader(
                "Live Project Map",
                "Verified project locations, status and synchronization state.");
        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.add(header, BorderLayout.WEST);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "Search project or marker");
        search.getAccessibleContext().setAccessibleName("Search project or marker");
        JLabel searchIcon = new JLabel(new LucideIcon("search", 15, () -> RmsColors.SECONDARY_TEXT));
        controls.add(searchIcon);
        statusFilter.setRenderer((list, value, index, selected, focused) -> {
            javax.swing.JLabel label = new javax.swing.JLabel(value == null ? "" : value.toString());
            label.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            if (selected) {
                label.setOpaque(true);
                label.setBackground(RmsColors.primaryLight());
                label.setForeground(RmsColors.text());
            }
            return label;
        });
        statusFilter.getAccessibleContext().setAccessibleName("Filter project markers by status");
        statusFilter.setToolTipText("Filter project markers by status");
        controls.add(new JLabel(new LucideIcon("filter", 15, () -> RmsColors.SECONDARY_TEXT)));
        JButton refresh = new JButton("Refresh");
        refresh.setIcon(new LucideIcon("refresh-cw", 15, RmsColors::primary));
        refresh.addActionListener(e -> {
            map.retryFailedTiles();
            refreshData();
        });
        controls.add(search);
        controls.add(statusFilter);
        controls.add(refresh);
        headerRow.add(controls, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(headerRow, BorderLayout.NORTH);

        locationSearch.putClientProperty("JTextField.placeholderText",
                "Search an address or place with Nominatim");
        locationSearch.setToolTipText("Search OpenStreetMap via Nominatim");
        locationResults.setPrototypeDisplayValue(new NominatimGeocoder.Result(
                "Select a location result", 0, 0, "", "", 0));
        locationResults.setRenderer((list, value, index, selected, focused) -> {
            javax.swing.JLabel label = new javax.swing.JLabel(value == null
                    ? "No location results"
                    : value.displayName());
            label.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            if (selected) {
                label.setOpaque(true);
                label.setBackground(RmsColors.primaryLight());
                label.setForeground(RmsColors.text());
            }
            return label;
        });
        searchLocation.setIcon(new LucideIcon("search", 15, RmsColors::primary));
        addLocation.setIcon(new LucideIcon("map-pin", 15, RmsColors::primary));
        addLocation.setEnabled(false);
        searchLocation.addActionListener(event -> searchLocation());
        locationSearch.addActionListener(event -> searchLocation());
        locationResults.addActionListener(event -> addLocation.setEnabled(
                locationResults.getSelectedItem() != null
                        && projectChoice.getSelectedItem() != null));
        projectChoice.addActionListener(event -> addLocation.setEnabled(
                locationResults.getSelectedItem() != null
                        && projectChoice.getSelectedItem() != null));
        addLocation.addActionListener(event -> addGeocodedLocation());

        JPanel geocodeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        geocodeRow.setOpaque(false);
        geocodeRow.add(projectChoice);
        geocodeRow.add(locationSearch);
        geocodeRow.add(searchLocation);
        geocodeRow.add(locationResults);
        geocodeRow.add(addLocation);
        north.add(geocodeRow, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.setOpaque(false);
        center.add(map, BorderLayout.CENTER);

        ModernTable.apply(table);
        JScrollPane tableScroll = new StyledScrollPane(table);
        tableScroll.setPreferredSize(new java.awt.Dimension(330, 0));
        tableScroll.setMinimumSize(new java.awt.Dimension(280, 0));
        center.add(tableScroll, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);

        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        add(status, BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        statusFilter.addActionListener(e -> applyFilter());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0 && row < shown.size()) map.select(shown.get(row));
            }
        });
        refreshData();
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(() -> {
            List<Project> projects = ctx.projects.findAll();
            List<Marker> markers = new ArrayList<>();
            if (ctx.constructionData != null) {
                for (JsonNode row : ctx.constructionData.rows("project_map_markers")) {
                    if (!row.hasNonNull("latitude") || !row.hasNonNull("longitude")) continue;
                    try {
                        BigDecimal latitude = row.get("latitude").decimalValue();
                        BigDecimal longitude = row.get("longitude").decimalValue();
                        if (latitude.compareTo(new BigDecimal("-90")) < 0
                                || latitude.compareTo(new BigDecimal("90")) > 0
                                || longitude.compareTo(new BigDecimal("-180")) < 0
                                || longitude.compareTo(new BigDecimal("180")) > 0) continue;
                        markers.add(new Marker(
                                uuid(row, "id"),
                                uuid(row, "project_id"),
                                text(row, "marker_name"),
                                latitude,
                                longitude,
                                text(row, "marker_type"),
                                text(row, "status"),
                                text(row, "description"),
                                text(row, "last_seen_at")));
                    } catch (RuntimeException ignored) {
                        // Invalid or incomplete coordinates are not treated as verified markers.
                    }
                }
            }
            markers.sort(Comparator.comparing(Marker::name, String.CASE_INSENSITIVE_ORDER));
            return new Loaded(projects, markers);
        }, this::fill, e -> status.setText(UiErrors.friendly(e)));
    }

    private void fill(Loaded loaded) {
        UUID previousProject = projectChoice.getSelectedItem() instanceof Choice choice
                ? choice.id() : null;
        projectChoice.removeAllItems();
        if (loaded.projects().isEmpty()) {
            projectChoice.addItem(new Choice(null, "Select project"));
        } else {
            for (Project project : loaded.projects()) {
                projectChoice.addItem(new Choice(project.id(), project.name()));
            }
            if (previousProject != null) {
                for (int i = 0; i < projectChoice.getItemCount(); i++) {
                    Choice choice = projectChoice.getItemAt(i);
                    if (previousProject.equals(choice.id())) {
                        projectChoice.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }
        map.setMarkers(loaded.markers());
        map.clearSelection();
        applyFilter(loaded.markers(), loaded.projects());
        status.setText(loaded.markers().isEmpty()
                ? "No verified project markers."
                : loaded.markers().size() + " verified marker(s).");
    }

    private void searchLocation() {
        String query = locationSearch.getText().trim();
        if (query.isEmpty()) {
            status.setText("Enter an address or place to search.");
            locationSearch.requestFocusInWindow();
            return;
        }
        searchLocation.setEnabled(false);
        status.setText("Searching locations…");
        BackgroundTask.run(() -> geocoder.search(query), results -> {
            searchLocation.setEnabled(true);
            locationResults.removeAllItems();
            for (NominatimGeocoder.Result result : results) {
                locationResults.addItem(result);
            }
            if (results.isEmpty()) {
                status.setText("No Nominatim locations matched.");
            } else {
                locationResults.setSelectedIndex(0);
                status.setText(results.size() + " location result(s).");
            }
        }, error -> {
            searchLocation.setEnabled(true);
            locationResults.removeAllItems();
            status.setText(error instanceof IOException
                    ? error.getMessage()
                    : UiErrors.friendly(error));
        });
    }

    private void addGeocodedLocation() {
        Object project = projectChoice.getSelectedItem();
        Object location = locationResults.getSelectedItem();
        if (!(project instanceof Choice projectChoiceValue)
                || projectChoiceValue.id() == null
                || !(location instanceof NominatimGeocoder.Result geocoded)) {
            status.setText("Select a project and location result.");
            return;
        }
        if (geocoded.latitude() < -90 || geocoded.latitude() > 90
                || geocoded.longitude() < -180 || geocoded.longitude() > 180) {
            status.setText("Nominatim returned coordinates outside valid latitude/longitude ranges.");
            return;
        }
        String markerName = geocoded.displayName();
        int comma = markerName.indexOf(',');
        if (comma > 0) markerName = markerName.substring(0, comma).trim();
        if (markerName.isBlank()) markerName = "Nominatim location";
        String finalMarkerName = markerName;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("project_id", projectChoiceValue.id());
        values.put("marker_name", finalMarkerName);
        values.put("latitude", geocoded.latitude());
        values.put("longitude", geocoded.longitude());
        values.put("marker_type", "Site");
        values.put("status", "Active");
        values.put("description", geocoded.displayName());
        addLocation.setEnabled(false);
        status.setText("Saving Nominatim location…");
        BackgroundTask.run(() -> {
            ctx.constructionData.saveRow("project_map_markers", null, values);
            return true;
        }, ignored -> {
            addLocation.setEnabled(true);
            locationSearch.setText("");
            locationResults.removeAllItems();
            refreshData();
        }, error -> {
            addLocation.setEnabled(true);
            status.setText(UiErrors.friendly(error));
        });
    }

    private void applyFilter() {
        Map<UUID, Project> projects = new HashMap<>();
        try {
            for (Project project : ctx.projects.findAll()) projects.put(project.id(), project);
        } catch (RuntimeException ignored) {
            // The table remains usable with marker names until the next successful refresh.
        }
        applyFilter(map.markers(), projects);
    }

    private void applyFilter(List<Marker> markers, List<Project> projects) {
        Map<UUID, Project> projectMap = new HashMap<>();
        for (Project project : projects) projectMap.put(project.id(), project);
        applyFilter(markers, projectMap);
    }

    private void applyFilter(List<Marker> markers, Map<UUID, Project> projects) {
        String query = search.getText().trim().toLowerCase();
        String statusChoice = (String) statusFilter.getSelectedItem();
        shown = new ArrayList<>();
        model.setRowCount(0);
        for (Marker marker : markers) {
            Project project = marker.projectId() == null ? null : projects.get(marker.projectId());
            String projectName = project == null ? "Unlinked project" : project.name();
            if (!"All".equals(statusChoice)
                    && !statusChoice.equalsIgnoreCase(marker.status())) continue;
            if (!query.isEmpty()
                    && !projectName.toLowerCase().contains(query)
                    && !marker.name.toLowerCase().contains(query)) continue;
            shown.add(marker);
            model.addRow(new Object[]{
                    projectName,
                    marker.name,
                    marker.status.isBlank() ? "N/A" : marker.status,
                    marker.latitude.toPlainString(),
                    marker.longitude.toPlainString()
            });
        }
        map.setMarkers(shown);
    }

    private static UUID uuid(JsonNode row, String field) {
        return row.hasNonNull(field) ? UUID.fromString(row.get(field).asText()) : null;
    }

    private static String text(JsonNode row, String field) {
        if (!row.hasNonNull(field)) return "";
        JsonNode value = row.get(field);
        if ("last_seen_at".equals(field) && value.isTextual()) {
            try {
                return OffsetDateTime.parse(value.asText()).toLocalDateTime().toString();
            } catch (RuntimeException ignored) {
                return value.asText();
            }
        }
        return value.asText();
    }

    private static final class MapPanel extends JPanel {
        private final OpenStreetMapTileLoader tiles = OpenStreetMapTileLoader.shared();
        private List<Marker> markers = new ArrayList<>();
        private Marker selected;
        private Point dragPoint;
        private int panX;
        private int panY;
        private int zoomOffset;
        private boolean tileLoadFailed;

        private MapPanel() {
            setBackground(RmsColors.background());
            setOpaque(true);
            setBorder(BorderFactory.createLineBorder(RmsColors.border()));
            setToolTipText("Verified coordinates load visible tiles from OpenStreetMap. Drag to pan; use the mouse wheel to zoom.");
            MouseAdapter navigation = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (event.getButton() == MouseEvent.BUTTON1) dragPoint = event.getPoint();
                }

                @Override public void mouseReleased(MouseEvent event) {
                    dragPoint = null;
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (dragPoint == null) return;
                    panX += event.getX() - dragPoint.x;
                    panY += event.getY() - dragPoint.y;
                    dragPoint = event.getPoint();
                    repaint();
                }

                @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                    zoomOffset = Math.max(-12, Math.min(12, zoomOffset - event.getWheelRotation()));
                    repaint();
                }
            };
            addMouseListener(navigation);
            addMouseMotionListener(navigation);
            addMouseWheelListener(navigation);
        }

        @Override public void updateUI() {
            super.updateUI();
            setBackground(RmsColors.background());
        }

        private List<Marker> markers() {
            return markers;
        }

        private void retryFailedTiles() {
            tiles.retryFailed();
            tileLoadFailed = false;
        }

        private void setMarkers(List<Marker> markers) {
            this.markers = new ArrayList<>(markers);
            if (selected != null && !this.markers.contains(selected)) selected = null;
            tileLoadFailed = false;
            repaint();
        }

        private void clearSelection() {
            selected = null;
        }

        private void select(Marker marker) {
            selected = marker;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(RmsColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            if (markers.isEmpty()) {
                drawLabel(g, "No verified project coordinates", 18, 28);
                drawLabel(g, "Add latitude and longitude through Project map.", 18, 54);
                drawAttribution(g);
                g.dispose();
                return;
            }

            List<Point2D.Double> locations = markers.stream()
                    .map(marker -> new Point2D.Double(marker.longitude.doubleValue(), marker.latitude.doubleValue()))
                    .toList();
            int fitZoom = WebMercator.zoomToFit(locations, getWidth(), getHeight());
            int zoom = Math.max(2, Math.min(18, fitZoom + zoomOffset));
            Point2D.Double center = WebMercator.center(locations, zoom);
            center.x -= panX;
            center.y -= panY;
            double left = center.x - getWidth() / 2.0;
            double top = center.y - getHeight() / 2.0;

            int loadedTiles = 0;
            List<WebMercator.Placement> visibleTiles =
                    WebMercator.visibleTiles(center, zoom, getWidth(), getHeight());
            tileLoadFailed = false;
            for (WebMercator.Placement placement : visibleTiles) {
                WebMercator.Tile tile = placement.tile();
                var image = tiles.cached(tile);
                if (image != null) {
                    g.drawImage(image, placement.screenX(), placement.screenY(), null);
                    loadedTiles++;
                } else if (isShowing() && !tiles.failedRecently(tile)) {
                    tiles.load(tile, result -> {
                        if (result.image() == null) tileLoadFailed = true;
                        repaint();
                    });
                } else if (tiles.failedRecently(tile)) {
                    tileLoadFailed = true;
                }
            }

            if (visibleTiles.isEmpty()) {
                drawLabel(g, "No map tiles at this latitude", 18, 28);
            } else if (loadedTiles == 0) {
                drawLabel(g, tileLoadFailed
                        ? "Map tiles unavailable. Check your connection and refresh."
                        : "Loading OpenStreetMap tiles…", 18, 28);
            } else {
                drawLabel(g, tileLoadFailed ? "Some map tiles could not load."
                        : "Verified project locations · " + markers.size() + " marker(s)", 18, 28);
            }

            for (Marker marker : markers) {
                Point2D.Double world = WebMercator.project(marker.latitude.doubleValue(),
                        marker.longitude.doubleValue(), zoom);
                int x = (int) Math.round(world.x - left);
                int y = (int) Math.round(world.y - top);
                boolean current = marker.equals(selected);
                int diameter = current ? 18 : 14;
                g.setColor(new java.awt.Color(0, 0, 0, 100));
                g.fillOval(x - diameter / 2 + 1, y - diameter / 2 + 2, diameter, diameter);
                g.setColor(statusColor(marker.status));
                g.fillOval(x - (current ? 10 : 7), y - (current ? 10 : 7),
                        current ? 20 : 14, current ? 20 : 14);
                g.setColor(java.awt.Color.WHITE);
                g.drawOval(x - diameter / 2, y - diameter / 2, diameter, diameter);
                drawLabel(g, marker.name, x + 13, y + 4);
            }

            if (selected != null) {
                int panelY = Math.max(48, getHeight() - 92);
                g.setColor(RmsColors.surface());
                g.fillRoundRect(18, panelY, Math.min(340, getWidth() - 36), 72, 12, 12);
                g.setColor(RmsColors.border());
                g.drawRoundRect(18, panelY, Math.min(340, getWidth() - 36), 72, 12, 12);
                drawLabel(g, selected.name, 30, panelY + 25);
                drawLabel(g, selected.status + " · " + selected.latitude + ", " + selected.longitude,
                        30, panelY + 49);
            }
            drawAttribution(g);
            g.dispose();
        }

        private void drawAttribution(Graphics2D g) {
            String attribution = "© OpenStreetMap contributors";
            int width = g.getFontMetrics().stringWidth(attribution) + 16;
            int x = Math.max(8, getWidth() - width - 8);
            int y = Math.max(20, getHeight() - 10);
            g.setColor(new java.awt.Color(6, 4, 53, 225));
            g.fillRoundRect(x, y - 17, width, 22, 8, 8);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(attribution, x + 8, y - 2);
        }

        private void drawLabel(Graphics2D g, String text, int x, int baseline) {
            String label = text == null ? "" : text;
            java.awt.FontMetrics metrics = g.getFontMetrics();
            int width = Math.min(metrics.stringWidth(label), Math.max(0, getWidth() - x - 8));
            if (width <= 0) return;
            int height = metrics.getHeight();
            g.setColor(new java.awt.Color(6, 4, 53, 210));
            g.fillRoundRect(x - 5, baseline - metrics.getAscent() - 3,
                    width + 10, height + 5, 8, 8);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(metrics.stringWidth(label) > width
                    ? label.substring(0, Math.min(label.length(), Math.max(1, width / 7))) + "…" : label,
                    x, baseline);
        }

        private static java.awt.Color statusColor(String status) {
            if ("Stopped".equalsIgnoreCase(status) || "At Risk".equalsIgnoreCase(status))
                return RmsColors.DANGER;
            if ("Delayed".equalsIgnoreCase(status)) return RmsColors.WARNING;
            if ("Completed".equalsIgnoreCase(status)) return RmsColors.SUCCESS;
            return RmsColors.primaryAccent();
        }
    }
}
