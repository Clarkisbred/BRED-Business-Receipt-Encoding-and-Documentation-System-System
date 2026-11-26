package com.bred.rms.importer;

/** Extracted receipt header fields editable during the administrator review step. */
public enum ReceiptField {
    DATE("receipt_date", "Receipt date"),
    TIN("tin", "TIN"),
    RECEIPT_NUMBER("receipt_number", "Receipt number"),
    SUPPLIER_NAME("supplier_name", "Supplier name"),
    SUPPLIER_ADDRESS("supplier_address", "Supplier address"),
    SUPPLIER_CONTACT("supplier_contact", "Supplier contact"),
    SUBTOTAL("subtotal", "Subtotal"),
    TAX("tax", "Tax"),
    DISCOUNT("discount", "Discount"),
    GRAND_TOTAL("grand_total", "Grand total"),
    CURRENCY("currency", "Currency");

    private final String jsonKey;
    private final String label;

    ReceiptField(String jsonKey, String label) {
        this.jsonKey = jsonKey;
        this.label = label;
    }

    public String jsonKey() {
        return jsonKey;
    }

    public String label() {
        return label;
    }
}
