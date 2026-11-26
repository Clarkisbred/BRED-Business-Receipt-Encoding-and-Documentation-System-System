package com.bred.rms.ui.receipts;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.Supplier;

import javax.swing.table.AbstractTableModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Rows of the receipt directory. Shows newest receipts first. */
public final class ReceiptTableModel extends AbstractTableModel {
    public static final String DASH = "—";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final String[] COLUMNS = {"Receipt No.", "Date", "Supplier", "Project", "Company", "Total", "Files"};

    private final List<Receipt> rows;
    private final Map<UUID, Supplier> suppliers;
    private final Map<UUID, Project> projects;
    private final Map<UUID, String> companies;

    public ReceiptTableModel(List<Receipt> receipts, Map<UUID, Supplier> suppliers, Map<UUID, Project> projects) {
        this(receipts, suppliers, projects, Map.of());
    }

    public ReceiptTableModel(List<Receipt> receipts, Map<UUID, Supplier> suppliers, Map<UUID, Project> projects,
                             Map<UUID, String> companies) {
        this.rows = receipts.stream()
                .sorted(Comparator.comparing(Receipt::date).reversed().thenComparing(Receipt::receiptNo))
                .toList();
        this.suppliers = suppliers;
        this.projects = projects;
        this.companies = Map.copyOf(companies);
    }

    public Receipt receiptAt(int row) { return rows.get(row); }

    @Override public int getRowCount() { return rows.size(); }

    @Override public int getColumnCount() { return COLUMNS.length; }

    @Override public String getColumnName(int c) { return COLUMNS[c]; }

    @Override public Object getValueAt(int row, int col) {
        Receipt r = rows.get(row);
        return switch (col) {
            case 0 -> r.receiptNo();
            case 1 -> DATE.format(r.date());
            case 2 -> { Supplier s = suppliers.get(r.supplierId()); yield s == null ? DASH : s.businessName(); }
            case 3 -> { Project p = r.projectId() == null ? null : projects.get(r.projectId()); yield p == null ? DASH : p.name(); }
            case 4 -> r.encodedBy() == null ? DASH : companies.getOrDefault(r.encodedBy(), DASH);
            case 5 -> ReceiptMath.format(r.grandTotal());
            default -> r.attachments().isEmpty() ? DASH : String.valueOf(r.attachments().size());
        };
    }

    /** Parses a date cell back to a date (for sorting). */
    public static LocalDate parseDate(String text) { return LocalDate.parse(text, DATE); }

    /** Parses a money cell such as "₱1,234.50" back to a number (for sorting). */
    public static BigDecimal parseMoney(String text) { return new BigDecimal(text.replace("₱", "").replace(",", "")); }
}
