package com.bred.rms.ui.receipts;

import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptMath;
import com.bred.rms.models.ValidationException;

import javax.swing.table.AbstractTableModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Editable line items of the receipt form. Rows that are still blank are ignored when saving. */
public final class LineItemsTableModel extends AbstractTableModel {
    public static final int DESCRIPTION = 0;
    public static final int QTY = 1;
    public static final int UNIT = 2;
    public static final int PRICE = 3;
    public static final int SUBTOTAL = 4;
    private static final String[] COLUMNS = {"Description", "Qty", "Unit", "Unit price", "Subtotal"};

    private static final class Row {
        String description = "";
        BigDecimal qty = BigDecimal.ONE;
        String unit = "pc";
        BigDecimal price = ReceiptMath.money(BigDecimal.ZERO);
    }

    private final List<Row> rows = new ArrayList<>();
    private java.util.function.Consumer<String> editMessageListener;
    private boolean lastRejected;

    public LineItemsTableModel(List<ReceiptItem> items) {
        for (ReceiptItem i : items) {
            Row r = new Row();
            r.description = i.description();
            r.qty = i.quantity();
            r.unit = i.unit();
            r.price = i.unitPrice();
            rows.add(r);
        }
    }

    public void addRow() {
        rows.add(new Row());
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
    }

    public void removeRow(int row) {
        rows.remove(row);
        fireTableRowsDeleted(row, row);
    }

    /** The items to save: everything except rows still left blank. */
    public List<ReceiptItem> items() {
        List<ReceiptItem> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.description.isBlank() && r.price.signum() == 0) continue;
            out.add(new ReceiptItem(r.description.trim(), r.qty, r.unit, r.price));
        }
        return out;
    }

    public BigDecimal subtotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Row r : rows) sum = sum.add(ReceiptMath.money(r.qty.multiply(r.price)));
        return ReceiptMath.money(sum);
    }

    /** True when the most recent edit was thrown away because it wasn't a number. */
    public boolean lastEditRejected() { return lastRejected; }

    /** Registers a listener told the reason after a rejected edit, and "" after an accepted one. */
    public void setEditMessageListener(java.util.function.Consumer<String> listener) { this.editMessageListener = listener; }

    @Override public int getRowCount() { return rows.size(); }

    @Override public int getColumnCount() { return COLUMNS.length; }

    @Override public String getColumnName(int c) { return COLUMNS[c]; }

    @Override public boolean isCellEditable(int row, int col) { return col != SUBTOTAL; }

    @Override public Object getValueAt(int row, int col) {
        Row r = rows.get(row);
        return switch (col) {
            case DESCRIPTION -> r.description;
            case QTY -> r.qty.stripTrailingZeros().toPlainString();
            case UNIT -> r.unit;
            case PRICE -> ReceiptMath.money(r.price).toPlainString();
            default -> ReceiptMath.money(r.qty.multiply(r.price)).toPlainString();
        };
    }

    @Override public void setValueAt(Object value, int row, int col) {
        Row r = rows.get(row);
        String text = value == null ? "" : value.toString();
        lastRejected = false;
        try {
            switch (col) {
                case DESCRIPTION -> r.description = text;
                case QTY -> r.qty = FormParsing.number("Quantity", text);
                case UNIT -> r.unit = text.isBlank() ? "pc" : text.trim();
                case PRICE -> r.price = ReceiptMath.money(FormParsing.number("Unit price", text));
                default -> { return; }
            }
            if (editMessageListener != null) editMessageListener.accept("");
        } catch (ValidationException e) {
            lastRejected = true;
            if (editMessageListener != null) editMessageListener.accept(e.getMessage());
        }
        fireTableRowsUpdated(row, row);
    }
}
