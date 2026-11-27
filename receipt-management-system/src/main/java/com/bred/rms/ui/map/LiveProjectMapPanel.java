package com.bred.rms.ui.map;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Project;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.ui.components.SectionHeader;
import com.bred.rms.ui.components.StyledScrollPane;
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
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Verified-coordinate project map workspace backed by project_map_markers. */
public final class LiveProjectMapPanel extends JPanel implements RefreshablePanel {
    private record Marker(UUID id, UUID projectId, String name, BigDecimal latitude,
                          BigDecimal longitude, String type, String status,
                          String description, String lastSeen) {}

    private record Loaded(List<Project> projects, List<Marker> markers) {}

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
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshData());
        controls.add(search);
        controls.add(statusFilter);
        controls.add(refresh);
        headerRow.add(controls, BorderLayout.EAST);
        add(headerRow, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.setOpaque(false);
        center.add(map, BorderLayout.CENTER);

        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
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
        map.setProjects(loaded.projects());
        map.setMarkers(loaded.markers());
        map.clearSelection();
        applyFilter(loaded.markers(), loaded.projects());
        status.setText(loaded.markers().isEmpty()
                ? "No verified project markers."
                : loaded.markers().size() + " verified marker(s).");
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
        private List<Project> projects = new ArrayList<>();
        private List<Marker> markers = new ArrayList<>();
        private Marker selected;

        private MapPanel() {
            setBackground(RmsColors.GLASS_BACKGROUND);
            setBorder(BorderFactory.createLineBorder(RmsColors.border()));
            setToolTipText("Verified project locations");
        }

        private void setProjects(List<Project> projects) {
            this.projects = projects;
        }

        private List<Marker> markers() {
            return markers;
        }

        private void setMarkers(List<Marker> markers) {
            this.markers = new ArrayList<>(markers);
            if (selected != null && !this.markers.contains(selected)) selected = null;
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

            g.setColor(RmsColors.GLASS_SIDEBAR);
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setColor(new java.awt.Color(90, 110, 150, 55));
            for (int x = 0; x < getWidth(); x += 40) g.drawLine(x, 0, x, getHeight());
            for (int y = 0; y < getHeight(); y += 40) g.drawLine(0, y, getWidth(), y);

            g.setColor(RmsColors.SECONDARY_TEXT);
            g.drawString(markers.isEmpty()
                    ? "No verified project coordinates"
                    : "Verified project coordinates • " + markers.size() + " marker(s)",
                    18, 28);

            if (markers.isEmpty()) {
                g.setColor(RmsColors.text());
                g.drawString("Add latitude and longitude through Project map.",
                        18, 52);
                g.dispose();
                return;
            }

            BigDecimal minLat = markers.stream().map(Marker::latitude)
                    .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal maxLat = markers.stream().map(Marker::latitude)
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal minLon = markers.stream().map(Marker::longitude)
                    .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal maxLon = markers.stream().map(Marker::longitude)
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            for (Marker marker : markers) {
                int x = relative(marker.longitude, minLon, maxLon, 40, getWidth() - 40);
                int y = relative(marker.latitude, minLat, maxLat, getHeight() - 40, 70);
                boolean current = marker.equals(selected);
                g.setColor(statusColor(marker.status));
                g.fillOval(x - (current ? 10 : 7), y - (current ? 10 : 7),
                        current ? 20 : 14, current ? 20 : 14);
                g.setColor(RmsColors.text());
                g.drawString(marker.name, x + 12, y + 4);
            }

            if (selected != null) {
                g.setColor(RmsColors.surface());
                g.fillRoundRect(18, getHeight() - 94, 270, 74, 12, 12);
                g.setColor(RmsColors.border());
                g.drawRoundRect(18, getHeight() - 94, 270, 74, 12, 12);
                g.setColor(RmsColors.text());
                g.drawString(selected.name, 30, getHeight() - 66);
                g.setColor(RmsColors.SECONDARY_TEXT);
                g.drawString(selected.status + " • " + selected.latitude + ", " + selected.longitude,
                        30, getHeight() - 44);
            }
            g.dispose();
        }

        private static int relative(BigDecimal value, BigDecimal min, BigDecimal max,
                                   int low, int high) {
            if (min.compareTo(max) == 0) return (low + high) / 2;
            BigDecimal ratio = value.subtract(min).divide(max.subtract(min), 6,
                    java.math.RoundingMode.HALF_UP);
            return low + ratio.multiply(BigDecimal.valueOf(high - low)).intValue();
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
