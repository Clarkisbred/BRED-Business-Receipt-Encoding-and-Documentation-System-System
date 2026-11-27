package com.bred.rms.models;

/** Kinds of receipt/invoice documents that can be encoded. */
public enum ReceiptType {
    OFFICIAL_RECEIPT("Official Receipt"), SALES_INVOICE("Sales Invoice"), CASH_INVOICE("Cash Invoice"),
    DELIVERY_RECEIPT("Delivery Receipt"), SALARY("Salary"), OTHER("Other");

    private final String label;

    ReceiptType(String label) { this.label = label; }

    /** Returns the display label. */
    public String label() { return label; }

    /** Returns the value stored in the database, e.g. "sales_invoice". */
    public String dbValue() { return name().toLowerCase(java.util.Locale.ROOT); }

    /** Reads a database value; anything unknown becomes {@link #OTHER}. */
    public static ReceiptType fromDb(String value) {
        for (ReceiptType t : values()) if (t.dbValue().equalsIgnoreCase(value)) return t;
        return OTHER;
    }

    @Override public String toString() { return label; }
}
