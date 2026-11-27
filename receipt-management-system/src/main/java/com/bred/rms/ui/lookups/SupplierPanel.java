package com.bred.rms.ui.lookups;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
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
import java.util.ArrayList;
import java.util.List;

/** Lists suppliers with Add / Edit and (admins only) Delete. */
public final class SupplierPanel extends JPanel implements RefreshablePanel {
    private static final String[] COLUMNS = {"Business name", "TIN", "Address", "Contact"};

    private final AppContext ctx;
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JButton edit = new JButton("Edit");
    private final JButton delete = new JButton("Delete");
    private List<Supplier> shown = new ArrayList<>();

    /** Builds the panel and loads suppliers. */
    public SupplierPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        JButton add = new JButton("Add supplier");
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
                if (e.getClickCount() == 2) selected().ifPresent(SupplierPanel.this::openForm);
            }
        });
        add(new StyledScrollPane(table), BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        updateButtons();
        refreshData();
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(ctx.suppliers::findAll, this::fill, e -> status.setText(UiErrors.friendly(e)));
    }

    private void fill(List<Supplier> suppliers) {
        shown = new ArrayList<>(suppliers);
        shown.sort((a, b) -> a.businessName().compareToIgnoreCase(b.businessName()));
        model.setRowCount(0);
        for (Supplier s : shown) model.addRow(new Object[]{s.businessName(), s.tin(), s.address(), s.contactNumber()});
        status.setText(shown.size() + " supplier(s)");
        updateButtons();
    }

    private java.util.Optional<Supplier> selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= shown.size() ? java.util.Optional.empty() : java.util.Optional.of(shown.get(row));
    }

    private void updateButtons() {
        boolean has = selected().isPresent();
        edit.setEnabled(has);
        delete.setEnabled(has);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void openForm(Supplier existing) {
        new SimpleFormDialog(SwingUtilities.getWindowAncestor(this), existing == null ? "Add supplier" : "Edit supplier",
                List.of("Business name", "TIN", "Address", "Contact number"),
                existing == null ? List.of("", "", "", "")
                        : List.of(nz(existing.businessName()), nz(existing.tin()), nz(existing.address()), nz(existing.contactNumber())),
                v -> ctx.suppliers.save(new Supplier(existing == null ? null : existing.id(), v.get(0), v.get(1), v.get(2), v.get(3))),
                this::refreshData).setVisible(true);
    }

    private void confirmDelete(Supplier s) {
        int answer = JOptionPane.showConfirmDialog(this, "Delete supplier \"" + s.businessName() + "\"?", "Delete supplier",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        BackgroundTask.run(() -> {
            ctx.suppliers.delete(s.id());
            return Boolean.TRUE;
        }, ok -> refreshData(), e -> JOptionPane.showMessageDialog(this, UiErrors.friendly(e), "Cannot delete supplier", JOptionPane.WARNING_MESSAGE));
    }
}
