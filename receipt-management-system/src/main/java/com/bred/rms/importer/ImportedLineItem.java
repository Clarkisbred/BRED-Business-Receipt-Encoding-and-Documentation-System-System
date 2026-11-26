package com.bred.rms.importer;

/** Editable line-item values staged for review; raw strings preserve uncertain or malformed AI values. */
public final class ImportedLineItem {
    private String description;
    private String quantity;
    private String unit;
    private String unitPrice;
    private String lineTotal;

    public ImportedLineItem(String description, String quantity, String unit, String unitPrice, String lineTotal) {
        this.description = text(description);
        this.quantity = text(quantity);
        this.unit = text(unit);
        this.unitPrice = text(unitPrice);
        this.lineTotal = text(lineTotal);
    }

    public String description() { return description; }
    public String quantity() { return quantity; }
    public String unit() { return unit; }
    public String unitPrice() { return unitPrice; }
    public String lineTotal() { return lineTotal; }

    public void setDescription(String value) { description = text(value); }
    public void setQuantity(String value) { quantity = text(value); }
    public void setUnit(String value) { unit = text(value); }
    public void setUnitPrice(String value) { unitPrice = text(value); }
    public void setLineTotal(String value) { lineTotal = text(value); }

    public ImportedLineItem copy() {
        return new ImportedLineItem(description, quantity, unit, unitPrice, lineTotal);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
