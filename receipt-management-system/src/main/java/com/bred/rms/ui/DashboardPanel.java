package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.Supplier;
import com.bred.rms.services.ReportService;
import com.bred.rms.ui.components.StatCard;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.theme.RmsColors;
import com.bred.rms.utils.BackgroundTask;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Headline numbers and the most recent receipts. Clicking a recent receipt opens it. */
public final class DashboardPanel extends JPanel implements RefreshablePanel {
    private static final int RECENT = 8;

    private record Loaded(List<Receipt> receipts, List<Supplier> suppliers) { }

    private final AppContext ctx;
    private final Consumer<UUID> onOpen;
    private final StatCard totalSpend = new StatCard("Total expenses", "–", RmsColors::primaryAccent);
    private final StatCard monthSpend = new StatCard("Spend this month", "–", RmsColors.SUCCESS);
    private final StatCard totalCount = new StatCard("Total receipts", "–", RmsColors.INFO);
    private final StatCard projectCount = new StatCard("Projects with spend", "–", RmsColors.WARNING);
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Receipt no.", "Date", "Supplier", "Total"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private List<Receipt> recent = new ArrayList<>();

    /** Builds the dashboard; {@code onOpen} is called with a receipt id when a recent receipt is clicked. */
    public DashboardPanel(AppContext ctx, Consumer<UUID> onOpen) {
        super(new BorderLayout(0, 14));
        this.ctx = ctx;
        this.onOpen = onOpen;
        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 0));
        cards.setOpaque(false);
        for (StatCard c : List.of(totalSpend, monthSpend, totalCount, projectCount)) {
            cards.add(c);
        }
        add(cards, BorderLayout.NORTH);

        JLabel heading = new JLabel("Recent receipts");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && row < recent.size()) DashboardPanel.this.onOpen.accept(recent.get(row).id());
            }
        });
        JPanel lower = new JPanel(new BorderLayout());
        lower.setOpaque(false);
        lower.add(heading, BorderLayout.NORTH);
        lower.add(new StyledScrollPane(table), BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        lower.add(status, BorderLayout.SOUTH);
        add(lower, BorderLayout.CENTER);
        refreshData();
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(() -> new Loaded(ctx.reports.receipts(null, null), ctx.suppliers.findAll()),
                this::fill, e -> status.setText(UiErrors.friendly(e)));
    }

    private void fill(Loaded loaded) {
        ReportService.Summary s = ReportService.summarize(loaded.receipts(), YearMonth.now());
        totalSpend.setValue(ReceiptMath.format(s.totalSpend()));
        monthSpend.setValue(ReceiptMath.format(s.monthSpend()));
        totalCount.setValue(String.valueOf(s.totalCount()));
        projectCount.setValue(String.valueOf(s.projectsWithSpend()));
        Map<UUID, String> names = new HashMap<>();
        for (Supplier sup : loaded.suppliers()) names.put(sup.id(), sup.businessName());
        recent = new ArrayList<>(loaded.receipts().subList(0, Math.min(RECENT, loaded.receipts().size())));
        model.setRowCount(0);
        for (Receipt r : recent) {
            model.addRow(new Object[]{r.receiptNo(), r.date().toString(), names.getOrDefault(r.supplierId(), "–"), ReceiptMath.format(r.grandTotal())});
        }
        status.setText(recent.isEmpty() ? "No receipts yet. Add your first one under Receipts." : "Click a receipt to open it.");
    }
}
