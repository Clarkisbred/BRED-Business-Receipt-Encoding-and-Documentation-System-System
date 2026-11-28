package com.bred.rms.models;

import java.util.UUID;

/** A business that issued receipts. */
public record Supplier(UUID id, String businessName, String tin, String address, String contactNumber,
                       String taxClassification) {
    private static final java.util.Set<String> TAX_CLASSIFICATIONS =
            java.util.Set.of("VAT_REGISTERED", "NON_VAT", "VAT_EXEMPT", "UNCLASSIFIED");

    public Supplier {
        taxClassification = taxClassification == null || taxClassification.isBlank()
                ? "UNCLASSIFIED" : taxClassification.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TAX_CLASSIFICATIONS.contains(taxClassification)) {
            throw new IllegalArgumentException("Choose VAT Registered, Non-VAT, VAT-exempt, or Unclassified.");
        }
    }

    public Supplier(UUID id, String businessName, String tin, String address, String contactNumber) {
        this(id, businessName, tin, address, contactNumber, "UNCLASSIFIED");
    }
}
