package com.bred.rms.models;

import java.math.BigDecimal;

/** One purchased line on a receipt. */
public record ReceiptItem(String description, BigDecimal quantity, String unit, BigDecimal unitPrice) {
    public ReceiptItem {
        unit = unit == null || unit.isBlank() ? "pc" : unit.trim();
    }

    public ReceiptItem(String description, BigDecimal quantity, BigDecimal unitPrice) {
        this(description, quantity, "pc", unitPrice);
    }

    /** Returns quantity × unit price, rounded to centavos. */
    public BigDecimal subtotal() { return ReceiptMath.money(quantity.multiply(unitPrice)); }
}
