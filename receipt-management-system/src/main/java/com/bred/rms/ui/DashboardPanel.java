package com.bred.rms.ui;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.models.ConstructionModels.ExpiryAlert;
import com.bred.rms.services.ReportService;
import com.bred.rms.models.Project;
import com.bred.rms.ui.components.StatCard;
import com.bred.rms.ui.components.StyledScrollPane;
import com.bred.rms.ui.components.GlassPanel;
import com.bred.rms.ui.components.SectionHeader;
import com.bred.rms.ui.components.UiConstants;
import com.bred.rms.ui.components.ModernTable;
import com.bred.rms.ui.components.SurfacePanel;
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
import java.awt.FlowLayout;
import java.awt.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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

    private record Loaded(List<Receipt> receipts, List<Supplier> suppliers, List<Project> projects,
                          List<ExpiryAlert> expiryAlerts, int failedTests, int openSafetyRecords,
                          com.bred.rms.services.ConstructionDataService.ConstructionSummary financial,
                          com.bred.rms.services.ConstructionDataService.BOQCostSummary boqCost) { }

    private final AppContext ctx;
    private final Consumer<UUID> onOpen;
    private final StatCard totalSpend = new StatCard("Total expenses", "–", RmsColors::primaryAccent);
    private final StatCard monthSpend = new StatCard("Spend this month", "–", RmsColors.SUCCESS);
    private final StatCard totalCount = new StatCard("Total receipts", "–", RmsColors.INFO);
    private final StatCard projectCount = new StatCard("Projects", "–", RmsColors.WARNING);
    private final StatCard complianceAlerts = new StatCard("Compliance alerts", "–", RmsColors.DANGER);
    private final StatCard receivable = new StatCard("Outstanding receivable", "–", RmsColors.INFO);
    private final StatCard overdueBillings = new StatCard("Overdue billings", "–", RmsColors.DANGER);
    private final StatCard boqEstimate = new StatCard("BOQ estimated", "–", RmsColors.INFO);
    private final StatCard boqVariance = new StatCard("BOQ cost variance", "–", RmsColors.WARNING);
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Project", "Receipts", "Spend"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final DefaultTableModel alertModel = new DefaultTableModel(
            new String[]{"State", "Category", "Item", "Expiry"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable alertTable = new JTable(alertModel);
    private final JLabel status = new JLabel(" ");
    private final JLabel grossBillings = new JLabel("Gross billings: –");
    private final JLabel collections = new JLabel("Collections: –");
    private final JLabel outstanding = new JLabel("Outstanding: –");
    private final JLabel overdueLabel = new JLabel("Overdue: –");
    private final JLabel boqCostLabel = new JLabel("BOQ cost variance: –");
    private List<Receipt> recent = new ArrayList<>();

    /** Builds the dashboard; {@code onOpen} is called with a receipt id when a recent receipt is clicked. */
    public DashboardPanel(AppContext ctx, Consumer<UUID> onOpen) {
        super(new BorderLayout(0, 14));
        this.ctx = ctx;
        this.onOpen = onOpen;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));

        User user = ctx.currentUser.get();
        String company = user == null || user.companyName().isBlank()
                ? "BRED workspace" : user.companyName();
        SectionHeader welcome = new SectionHeader(
                "Good " + dayPart() + (user == null || user.fullName().isBlank()
                        ? "" : ", " + user.fullName()),
                company + "  •  " + LocalDate.now().format(DateTimeFormatter
                        .ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)));
        add(welcome, BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(1, 4, UiConstants.CARD_GAP, UiConstants.CARD_GAP));
        cards.setOpaque(false);
        for (StatCard card : List.of(totalSpend, monthSpend, projectCount, boqVariance)) {
            cards.add(card);
        }

        JPanel lower = new JPanel(new GridLayout(1, 3, UiConstants.CARD_GAP, 0));
        lower.setOpaque(false);
        lower.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));

        SurfacePanel projectPanel = new SurfacePanel(new BorderLayout(0, 8), RmsColors::surface);
        projectPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border()),
                BorderFactory.createEmptyBorder(UiConstants.PANEL_PADDING,
                        UiConstants.PANEL_PADDING, UiConstants.PANEL_PADDING,
                        UiConstants.PANEL_PADDING)));
        JLabel projectHeading = new JLabel("Project overview");
        projectHeading.setFont(projectHeading.getFont().deriveFont(Font.BOLD, 15f));
        projectHeading.setForeground(RmsColors.text());
        projectPanel.add(projectHeading, BorderLayout.NORTH);
        table.setRowHeight(32);
        table.setFillsViewportHeight(true);
        ModernTable.apply(table);
        projectPanel.add(new StyledScrollPane(table), BorderLayout.CENTER);
        lower.add(projectPanel);

        SurfacePanel financialPanel = new SurfacePanel(new BorderLayout(0, 10), RmsColors::surface);
        financialPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border()),
                BorderFactory.createEmptyBorder(UiConstants.PANEL_PADDING,
                        UiConstants.PANEL_PADDING, UiConstants.PANEL_PADDING,
                        UiConstants.PANEL_PADDING)));
        financialPanel.setLayout(new BorderLayout(0, 10));
        JLabel financialHeading = new JLabel("Financial overview");
        financialHeading.setFont(financialHeading.getFont().deriveFont(Font.BOLD, 15f));
        financialHeading.setForeground(RmsColors.text());
        financialPanel.add(financialHeading, BorderLayout.NORTH);
        JPanel financialRows = new JPanel(new GridLayout(5, 1, 4, 8));
        financialRows.setOpaque(false);
        for (JLabel label : List.of(grossBillings, collections, outstanding, overdueLabel, boqCostLabel)) {
            label.setForeground(RmsColors.text());
            financialRows.add(label);
        }
        financialPanel.add(financialRows, BorderLayout.CENTER);
        lower.add(financialPanel);

        SurfacePanel activityPanel = new SurfacePanel(new BorderLayout(0, 8), RmsColors::surface);
        activityPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(RmsColors.border()),
                BorderFactory.createEmptyBorder(UiConstants.PANEL_PADDING,
                        UiConstants.PANEL_PADDING, UiConstants.PANEL_PADDING,
                        UiConstants.PANEL_PADDING)));
        JLabel alertsHeading = new JLabel("Compliance and project alerts");
        alertsHeading.setFont(alertsHeading.getFont().deriveFont(Font.BOLD, 15f));
        alertsHeading.setForeground(RmsColors.text());
        alertTable.setRowHeight(28);
        alertTable.setFillsViewportHeight(true);
        ModernTable.apply(alertTable);
        activityPanel.add(alertsHeading, BorderLayout.NORTH);
        activityPanel.add(new StyledScrollPane(alertTable), BorderLayout.CENTER);
        lower.add(activityPanel);

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(cards, BorderLayout.CENTER);
        center.add(lower, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        status.setForeground(RmsColors.SECONDARY_TEXT);
        add(status, BorderLayout.SOUTH);
        refreshData();
    }

    @Override
    public void refreshData() {
        BackgroundTask.run(() -> new Loaded(
                        ctx.reports.receipts(null, null),
                        ctx.suppliers.findAll(),
                        ctx.projects.findAll(),
                        ctx.constructionData.expiryAlerts(),
                        ctx.constructionData.failedTestCount(),
                        ctx.constructionData.openSafetyCount(),
                        ctx.constructionData.summary(),
                        ctx.constructionData.boqCostSummary()),
                this::fill, e -> status.setText(UiErrors.friendly(e)));
    }

    private void fill(Loaded loaded) {
        ReportService.Summary s = ReportService.summarize(loaded.receipts(), YearMonth.now());
        YearMonth previousMonth = YearMonth.now().minusMonths(1);
        ReportService.Summary previous = ReportService.summarize(loaded.receipts(), previousMonth);
        totalSpend.setValue(ReceiptMath.format(s.totalSpend()));
        monthSpend.setValue(ReceiptMath.format(s.monthSpend()));
        if (previous.monthSpend().signum() == 0) {
            monthSpend.setTrend(null, false);
        } else {
            BigDecimal monthDelta = s.monthSpend().subtract(previous.monthSpend())
                    .divide(previous.monthSpend().abs(), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            monthSpend.setTrend(monthDelta, false);
        }
        totalCount.setValue(String.valueOf(s.totalCount()));
        projectCount.setValue(String.valueOf(loaded.projects().size()));
        projectCount.setTrend(null, true);
        complianceAlerts.setValue(String.valueOf(loaded.expiryAlerts().size()));
        receivable.setValue(ReceiptMath.format(loaded.financial().receivable()));
        overdueBillings.setValue(String.valueOf(loaded.financial().overdueBillings()));
        boqEstimate.setValue(ReceiptMath.format(loaded.boqCost().estimated()));
        boqVariance.setValue(loaded.boqCost().variancePercent() == null
                ? loaded.boqCost().status()
                : ReceiptMath.format(loaded.boqCost().variance())
                  + " (" + loaded.boqCost().variancePercent().setScale(2, java.math.RoundingMode.HALF_UP) + "%)");
        boqVariance.setTrend(loaded.boqCost().variancePercent(), false);
        grossBillings.setText("Gross billings: " + ReceiptMath.format(loaded.financial().grossBillings()));
        collections.setText("Collections: " + ReceiptMath.format(loaded.financial().collected()));
        outstanding.setText("Outstanding: " + ReceiptMath.format(loaded.financial().receivable()));
        overdueLabel.setText("Overdue billings: " + loaded.financial().overdueBillings());
        boqCostLabel.setText("BOQ cost variance: "
                + (loaded.boqCost().variancePercent() == null
                        ? loaded.boqCost().status()
                        : loaded.boqCost().variancePercent()
                                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%"));
        Map<UUID, String> names = new HashMap<>();
        for (Supplier sup : loaded.suppliers()) names.put(sup.id(), sup.businessName());
        model.setRowCount(0);
        Map<UUID, Integer> counts = new HashMap<>();
        Map<UUID, BigDecimal> spends = new HashMap<>();
        for (Receipt receipt : loaded.receipts()) {
            if (receipt.projectId() == null) continue;
            counts.merge(receipt.projectId(), 1, Integer::sum);
            spends.merge(receipt.projectId(), receipt.grandTotal(), BigDecimal::add);
        }
        for (Project project : loaded.projects()) {
            model.addRow(new Object[]{
                    project.name(),
                    counts.getOrDefault(project.id(), 0),
                    ReceiptMath.format(spends.getOrDefault(project.id(), BigDecimal.ZERO))
            });
        }
        alertModel.setRowCount(0);
        for (ExpiryAlert alert : loaded.expiryAlerts()) {
            alertModel.addRow(new Object[]{alert.label(), alert.category(), alert.name(),
                    alert.expiryDate().toString()});
        }
        status.setText(loaded.projects().isEmpty()
                ? "No projects yet. Add your first project under Projects."
                : "Dashboard uses authorized company data.");
    }

    private static String dayPart() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 12) return "morning";
        if (hour < 18) return "afternoon";
        return "evening";
    }
}
