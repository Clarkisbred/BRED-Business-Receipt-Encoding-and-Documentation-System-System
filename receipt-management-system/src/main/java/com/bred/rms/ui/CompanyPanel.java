package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Company;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.ModernTable;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.services.ReceiptQuery;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creator-only view and management for companies across the system. */
public final class CompanyPanel extends JPanel implements RefreshablePanel {
    record CompanySummary(Company company, BigDecimal expenses, int admins, int encoders, int totalReceipts) { }

    private final AppContext ctx;
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Company", "Expenses", "Admins", "Encoders", "Total receipts"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JButton edit = new JButton("Edit");
    private final JButton delete = new JButton("Delete");
    private final JLabel status = new JLabel(" ");
    private List<CompanySummary> shown = List.of();

    public CompanyPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        JButton reload = new JButton("Refresh");
        reload.addActionListener(event -> refreshData());
        edit.addActionListener(event -> selected().ifPresent(this::editCompany));
        delete.addActionListener(event -> selected().ifPresent(this::deleteCompany));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(edit);
        actions.add(delete);
        actions.add(reload);
        add(actions, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ModernTable.apply(table);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> updateButtons());
        add(new StyledScrollPane(table), BorderLayout.CENTER);

        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        updateButtons();
        refreshData();
    }

    @Override public void refreshData() {
        status.setText("Loading companies...");
        BackgroundTask.run(() -> summarize(ctx.companies.list(), ctx.users.list(),
                        ctx.receipts.search(ReceiptQuery.all())),
                this::fill, error -> status.setText(UiErrors.friendly(error)));
    }

    static List<CompanySummary> summarize(List<Company> companies, List<User> users, List<Receipt> receipts) {
        Map<UUID, CompanySummary> summaries = new LinkedHashMap<>();
        for (Company company : companies) {
            summaries.put(company.id(), new CompanySummary(company, BigDecimal.ZERO, 0, 0, 0));
        }
        for (User user : users) {
            if (!user.active() || user.companyId() == null) continue;
            CompanySummary summary = summaries.get(user.companyId());
            if (summary == null) continue;
            if (user.role() == Role.ADMIN) {
                summaries.put(user.companyId(), new CompanySummary(summary.company(), summary.expenses(),
                        summary.admins() + 1, summary.encoders(), summary.totalReceipts()));
            } else if (user.role() == Role.ENCODER) {
                summaries.put(user.companyId(), new CompanySummary(summary.company(), summary.expenses(),
                        summary.admins(), summary.encoders() + 1, summary.totalReceipts()));
            }
        }
        for (Receipt receipt : receipts) {
            CompanySummary summary = summaries.get(receipt.companyId());
            if (summary == null) continue;
            summaries.put(receipt.companyId(), new CompanySummary(summary.company(),
                    summary.expenses().add(receipt.grandTotal()), summary.admins(), summary.encoders(),
                    summary.totalReceipts() + 1));
        }
        return List.copyOf(summaries.values());
    }

    private void fill(List<CompanySummary> companies) {
        shown = new ArrayList<>(companies);
        model.setRowCount(0);
        for (CompanySummary summary : shown) {
            model.addRow(new Object[]{summary.company().name(), ReceiptMath.format(summary.expenses()),
                    summary.admins(), summary.encoders(), summary.totalReceipts()});
        }
        status.setText(shown.size() + " company(s)");
        updateButtons();
    }

    private java.util.Optional<Company> selected() {
        int viewRow = table.getSelectedRow();
        int row = viewRow < 0 ? -1 : table.convertRowIndexToModel(viewRow);
        return row < 0 || row >= shown.size() ? java.util.Optional.empty()
                : java.util.Optional.of(shown.get(row).company());
    }

    private void updateButtons() {
        boolean hasSelection = table.getSelectedRow() >= 0;
        edit.setEnabled(hasSelection);
        delete.setEnabled(hasSelection);
    }

    private void editCompany(Company company) {
        JTextField name = new JTextField(company.name(), 28);
        int answer = JOptionPane.showConfirmDialog(this, name, "Edit company",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        edit.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.companies.update(company.id(), name.getText());
            return Boolean.TRUE;
        }, ignored -> refreshData(), error -> {
            status.setText(UiErrors.friendly(error));
            updateButtons();
        });
    }

    private void deleteCompany(Company company) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Permanently delete " + company.name() + "?\nCompanies used by accounts or receipt data cannot be deleted.",
                "Delete company", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        delete.setEnabled(false);
        BackgroundTask.run(() -> {
            ctx.companies.delete(company.id());
            return Boolean.TRUE;
        }, ignored -> refreshData(), error -> {
            status.setText(UiErrors.friendly(error));
            updateButtons();
        });
    }
}
