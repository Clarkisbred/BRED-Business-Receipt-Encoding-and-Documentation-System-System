package com.bred.rms.importer;

import javax.swing.table.AbstractTableModel;
import java.util.List;

/** Editable line-item view for a transient receipt candidate. */
public final class ImportedLineItemsTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"Description", "Quantity", "Unit", "Unit price", "Line total"};
    private final List<ImportedLineItem> items;
    private final Runnable changed;

    public ImportedLineItemsTableModel(ReceiptCandidate candidate, Runnable changed) {
        this.items = candidate.lineItems();
        this.changed = changed;
    }

    public void addItem() {
        items.add(new ImportedLineItem("", "", "", "", ""));
        int row = items.size() - 1;
        fireTableRowsInserted(row, row);
        changed.run();
    }

    public void removeItem(int row) {
        if (row < 0 || row >= items.size()) return;
        items.remove(row);
        fireTableRowsDeleted(row, row);
        changed.run();
    }

    public List<ImportedLineItem> items() {
        return List.copyOf(items);
    }

    @Override public int getRowCount() { return items.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public boolean isCellEditable(int row, int column) { return true; }

    @Override
    public Object getValueAt(int row, int column) {
        ImportedLineItem item = items.get(row);
        return switch (column) {
            case 0 -> item.description();
            case 1 -> item.quantity();
            case 2 -> item.unit();
            case 3 -> item.unitPrice();
            case 4 -> item.lineTotal();
            default -> throw new IndexOutOfBoundsException("Unknown line-item column.");
        };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        ImportedLineItem item = items.get(row);
        String text = value == null ? "" : value.toString();
        switch (column) {
            case 0 -> item.setDescription(text);
            case 1 -> item.setQuantity(text);
            case 2 -> item.setUnit(text);
            case 3 -> item.setUnitPrice(text);
            case 4 -> item.setLineTotal(text);
            default -> throw new IndexOutOfBoundsException("Unknown line-item column.");
        }
        fireTableCellUpdated(row, column);
        changed.run();
    }
}
