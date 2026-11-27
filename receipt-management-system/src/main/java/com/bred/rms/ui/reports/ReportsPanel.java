package com.bred.rms.ui.reports;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.services.ReportService.Total;
import com.bred.rms.services.ConstructionDataService.ConstructionReportRow;
import com.bred.rms.ui.RefreshablePanel;
import com.bred.rms.ui.UiErrors;
import com.bred.rms.ui.components.DateField;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Totals by project, supplier or month for a date range, with CSV export of the underlying receipts. */
public final class ReportsPanel extends JPanel implements RefreshablePanel {
    private static final String BY_PROJECT = "By project";
    private static final String BY_SUPPLIER = "By supplier";
    private static final String BY_MONTH = "By month";
    private static final String COMPLIANCE_EXPIRY = "Compliance expiry";
    private static final String FAILED_TESTS = "Failed tests";
    private static final String OPEN_SAFETY = "Open safety";
    private static final String BID_SUMMARY = "Bid summary";
    private static final String SUBCONTRACT_BALANCES = "Subcontract balances";
    private static final String EQUIPMENT_COSTS = "Equipment costs";
    private static final String CASH_ADVANCES = "Cash advances";
    private static final String TAX_CALENDAR = "Tax calendar";
    private static final String PROGRESS = "Progress and accomplishments";
    private static final String MILESTONES = "Milestones";
    private static final String BILLING_POSITION = "Billing position";
    private static final String COLLECTIONS_REPORT = "Collections";
    private static final String OVERDUE_BILLINGS = "Overdue billings";
    private static final String PROJECT_PL = "Project P&L";
    private static final String PAYABLES_REPORT = "Payables";
    private static final String CASH_FLOW_REPORT = "Cash flow";

    private final AppContext ctx;
    private final DateField from = new DateField();
    private final DateField to = new DateField();
    private final JComboBox<String> groupBy = new JComboBox<>(new String[]{
            BY_PROJECT, BY_SUPPLIER, BY_MONTH, COMPLIANCE_EXPIRY, FAILED_TESTS, OPEN_SAFETY,
            BID_SUMMARY, SUBCONTRACT_BALANCES, EQUIPMENT_COSTS, CASH_ADVANCES, TAX_CALENDAR,
            PROGRESS, MILESTONES, BILLING_POSITION, COLLECTIONS_REPORT, OVERDUE_BILLINGS,
            PROJECT_PL, PAYABLES_REPORT, CASH_FLOW_REPORT});
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Group", "Receipts", "Total"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JButton export = new JButton("Export CSV");
    private final JButton exportExcel = new JButton("Export Excel");
    private int requestId;

    /** Builds the panel and runs the default report. */
    public ReportsPanel(AppContext ctx) {
        super(new BorderLayout(0, 10));
        this.ctx = ctx;
        JButton run = new JButton("Show");
        run.addActionListener(e -> refreshData());
        export.addActionListener(e -> exportReceipts(false));
        exportExcel.addActionListener(e -> exportReceipts(true));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.add(new JLabel("From"));
        bar.add(from);
        bar.add(new JLabel("To"));
        bar.add(to);
        bar.add(groupBy);
        bar.add(run);
        bar.add(export);
        bar.add(exportExcel);
        add(bar, BorderLayout.NORTH);
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        add(new StyledScrollPane(table), BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        add(status, BorderLayout.SOUTH);
        groupBy.addActionListener(e -> refreshData());
        refreshData();
    }

    /** Returns the chosen range, or null (after showing a message) when a date is malformed. */
    private LocalDate[] range() {
        if (!from.isValidInput() || !to.isValidInput()) {
            status.setText("Enter dates as YYYY-MM-DD, or leave them empty.");
            return null;
        }
        LocalDate f = from.value();
        LocalDate t = to.value();
        if (f != null && t != null && f.isAfter(t)) {
            status.setText("The start date is after the end date.");
            return null;
        }
        return new LocalDate[]{f, t};
    }

    @Override
    public void refreshData() {
        LocalDate[] r = range();
        if (r == null) return;
        String kind = (String) groupBy.getSelectedItem();
        int my = ++requestId;
        status.setText("Loading…");
        if (isConstructionReport(kind)) {
            BackgroundTask.run(() -> ctx.constructionData.constructionReport(kind),
                    rows -> { if (my == requestId) fillConstruction(rows); },
                    e -> { if (my == requestId) status.setText(UiErrors.friendly(e)); });
        } else {
            BackgroundTask.run(() -> switch (kind == null ? BY_PROJECT : kind) {
                case BY_SUPPLIER -> ctx.reports.totalsBySupplier(r[0], r[1]);
                case BY_MONTH -> ctx.reports.totalsByMonth(r[0], r[1]);
                default -> ctx.reports.totalsByProject(r[0], r[1]);
            }, totals -> { if (my == requestId) fill(totals); },
                    e -> { if (my == requestId) status.setText(UiErrors.friendly(e)); });
        }
    }

    private void fill(List<Total> totals) {
        model.setRowCount(0);
        int count = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (Total t : totals) {
            model.addRow(new Object[]{t.label(), t.receiptCount(), ReceiptMath.format(t.amount())});
            count += t.receiptCount();
            sum = sum.add(t.amount());
        }
        status.setText(count + " receipt(s), total " + ReceiptMath.format(sum));
    }

    private boolean isConstructionReport(String kind) {
        return List.of(COMPLIANCE_EXPIRY, FAILED_TESTS, OPEN_SAFETY, BID_SUMMARY,
                SUBCONTRACT_BALANCES, EQUIPMENT_COSTS, CASH_ADVANCES, TAX_CALENDAR,
                PROGRESS, MILESTONES, BILLING_POSITION, COLLECTIONS_REPORT, OVERDUE_BILLINGS,
                PROJECT_PL, PAYABLES_REPORT, CASH_FLOW_REPORT).contains(kind);
    }

    private void fillConstruction(List<ConstructionReportRow> rows) {
        model.setRowCount(0);
        BigDecimal total = BigDecimal.ZERO;
        for (ConstructionReportRow row : rows) {
            model.addRow(new Object[]{row.label(), row.count(),
                    row.amount().signum() == 0 ? row.status() : ReceiptMath.format(row.amount())});
            total = total.add(row.amount());
        }
        status.setText(rows.size() + " construction row(s)" +
                (total.signum() == 0 ? "." : ", amount " + ReceiptMath.format(total)));
    }

    private void exportReceipts(boolean excel) {
        LocalDate[] r = range();
        if (r == null) return;
        JFileChooser chooser = new JFileChooser();
        String ext = excel ? ".xlsx" : ".csv";
        chooser.setSelectedFile(new File("receipts" + ext));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = chooser.getSelectedFile();
        File target = chosen.getName().toLowerCase().endsWith(ext)
                ? chosen : new File(chosen.getParentFile(), chosen.getName() + ext);
        export.setEnabled(false);
        exportExcel.setEnabled(false);
        BackgroundTask.run(() -> {
            List<Receipt> receipts = ctx.reports.receipts(r[0], r[1]);
            if (excel) ctx.reports.exportXlsx(receipts, target.toPath());
            else ctx.reports.exportCsv(receipts, target.toPath());
            return receipts.size();
        }, n -> {
            export.setEnabled(true);
            exportExcel.setEnabled(true);
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this), n + " receipt(s) exported to\n" + target.getAbsolutePath(),
                    "Export complete", JOptionPane.INFORMATION_MESSAGE);
        }, e -> {
            export.setEnabled(true);
            exportExcel.setEnabled(true);
            status.setText(e instanceof java.io.IOException ? "Could not write the file: " + e.getMessage() : UiErrors.friendly(e));
        });
    }
}
