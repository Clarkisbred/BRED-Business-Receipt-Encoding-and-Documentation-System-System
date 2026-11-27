package com.bred.rms.ui.lookups;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.services.ReceiptQuery;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.LucideIcon;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Lists projects with their total spend; Add / Edit and (admins only) Delete. */
public final class ProjectPanel extends JPanel implements RefreshablePanel {
    private static final String[] COLUMNS = {"Project", "Code", "Fund source", "Receipts", "Total spend"};

    private record Loaded(List<Project> projects, List<Receipt> receipts) { }

    private final AppContext ctx;
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JButton edit = new JButton("Edit");
    private final JButton delete = new JButton("Delete");
    private List<Project> shown = new ArrayList<>();

    /** Builds the panel and loads projects. */
    public ProjectPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        JButton add = new JButton("Add project");
        add.setIcon(new LucideIcon("plus", 16, RmsColors::primary));
        add.addActionListener(e -> openForm(null));
        edit.setIcon(new LucideIcon("pencil", 15, RmsColors::primary));
        delete.setIcon(new LucideIcon("trash-2", 15, () -> RmsColors.DANGER));
        edit.addActionListener(e -> selected().ifPresent(this::openForm));
        delete.addActionListener(e -> selected().ifPresent(this::confirmDelete));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.add(add);
        bar.add(edit);
        User user = ctx.currentUser.get();
        if (user != null && user.role().canManageCompany()) bar.add(delete);
        add(bar, BorderLayout.NORTH);

        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) selected().ifPresent(ProjectPanel.this::openForm);
            }
        });
        add(new StyledScrollPane(table), BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        updateButtons();
        refreshData();
    }

    /** Sums grand totals per project id; receipts without a project are skipped. */
    public static Map<UUID, BigDecimal> spendByProject(List<Receipt> receipts) {
        Map<UUID, BigDecimal> spend = new HashMap<>();
        for (Receipt r : receipts) {
            if (r.projectId() != null) spend.merge(r.projectId(), r.grandTotal(), BigDecimal::add);
        }
        return spend;
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(() -> new Loaded(ctx.projects.findAll(), ctx.receipts.search(ReceiptQuery.all())),
                this::fill, e -> status.setText(UiErrors.friendly(e)));
    }

    private void fill(Loaded loaded) {
        shown = new ArrayList<>(loaded.projects());
        shown.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        Map<UUID, BigDecimal> spend = spendByProject(loaded.receipts());
        Map<UUID, Long> counts = new HashMap<>();
        for (Receipt r : loaded.receipts()) if (r.projectId() != null) counts.merge(r.projectId(), 1L, Long::sum);
        model.setRowCount(0);
        for (Project p : shown) {
            model.addRow(new Object[]{p.name(), p.code(), p.fundSource(), counts.getOrDefault(p.id(), 0L),
                    ReceiptMath.format(spend.getOrDefault(p.id(), BigDecimal.ZERO))});
        }
        status.setText(shown.size() + " project(s)");
        updateButtons();
    }

    private Optional<Project> selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= shown.size() ? Optional.empty() : Optional.of(shown.get(row));
    }

    private void updateButtons() {
        boolean has = selected().isPresent();
        edit.setEnabled(has);
        delete.setEnabled(has);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void openForm(Project existing) {
        new SimpleFormDialog(SwingUtilities.getWindowAncestor(this), existing == null ? "Add project" : "Edit project",
                List.of("Project name", "Project code", "Fund source"),
                existing == null ? List.of("", "", "") : List.of(nz(existing.name()), nz(existing.code()), nz(existing.fundSource())),
                v -> ctx.projects.save(new Project(existing == null ? null : existing.id(), v.get(0), v.get(1), v.get(2))),
                this::refreshData).setVisible(true);
    }

    private void confirmDelete(Project p) {
        int answer = JOptionPane.showConfirmDialog(this, "Delete project \"" + p.name() + "\"?", "Delete project",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        BackgroundTask.run(() -> {
            ctx.projects.delete(p.id());
            return Boolean.TRUE;
        }, ok -> refreshData(), e -> JOptionPane.showMessageDialog(this, UiErrors.friendly(e), "Cannot delete project", JOptionPane.WARNING_MESSAGE));
    }
}
