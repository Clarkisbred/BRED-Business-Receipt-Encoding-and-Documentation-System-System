package com.bred.rms.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An encoded receipt with its line items, charges and attachments. {@code projectId} may be null. */
public record Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId,
                      List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                      List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt,
                      UUID companyId, String notes, String paymentMethod, BigDecimal ewtRate, String classification,
                      String supplierTaxClassification) {
    public Receipt {
        items = items == null ? List.of() : List.copyOf(items);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        discount = discount == null ? BigDecimal.ZERO : discount;
        vat = vat == null ? BigDecimal.ZERO : vat;
        additionalCharges = additionalCharges == null ? BigDecimal.ZERO : additionalCharges;
        notes = notes == null ? "" : notes;
        paymentMethod = paymentMethod == null || paymentMethod.isBlank() ? "Cash" : paymentMethod;
        ewtRate = ewtRate == null ? BigDecimal.ZERO : ewtRate;
        classification = classification == null || classification.isBlank()
                ? "UNCLASSIFIED" : classification.trim().toUpperCase(java.util.Locale.ROOT);
        supplierTaxClassification = supplierTaxClassification == null || supplierTaxClassification.isBlank()
                ? "UNCLASSIFIED" : supplierTaxClassification.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("VAT_REGISTERED", "NON_VAT", "VAT_EXEMPT", "UNCLASSIFIED")
                .contains(supplierTaxClassification)) {
            throw new IllegalArgumentException("Choose a valid supplier tax classification.");
        }
    }

    /** Compatibility constructor for callers without a supplier tax-classification snapshot. */
    public Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId,
                   List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                   List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt,
                   UUID companyId, String notes, String paymentMethod, BigDecimal ewtRate, String classification) {
        this(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                attachments, encodedBy, createdAt, modifiedAt, companyId, notes, paymentMethod, ewtRate,
                classification, "UNCLASSIFIED");
    }

    /** Compatibility constructor for callers without an expense classification. */
    public Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId,
                   List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                   List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt,
                   UUID companyId, String notes, String paymentMethod, BigDecimal ewtRate) {
        this(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                attachments, encodedBy, createdAt, modifiedAt, companyId, notes, paymentMethod, ewtRate,
                "UNCLASSIFIED", "UNCLASSIFIED");
    }

    /** Compatibility constructor for callers without extended receipt details. */
    public Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId,
                   List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                   List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt,
                   UUID companyId) {
        this(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                attachments, encodedBy, createdAt, modifiedAt, companyId, "", "Cash", BigDecimal.ZERO);
    }

    /** Compatibility constructor for callers without persisted company scope. */
    public Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId,
                   List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                   List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt) {
        this(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                attachments, encodedBy, createdAt, modifiedAt, null, "", "Cash", BigDecimal.ZERO);
    }

    /** Returns the sum of all item subtotals. */
    public BigDecimal subtotal() {
        return ReceiptMath.money(items.stream().map(ReceiptItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Returns subtotal − discount + VAT + additional charges. */
    public BigDecimal grandTotal() {
        return ReceiptMath.money(subtotal().subtract(discount).add(vat).add(additionalCharges));
    }

    /** EWT uses the saved VAT-exclusive base for VAT-registered suppliers, otherwise the saved gross base. */
    public BigDecimal ewtAmount() {
        BigDecimal base = "VAT_REGISTERED".equals(supplierTaxClassification)
                ? grandTotal().subtract(vat).max(BigDecimal.ZERO) : grandTotal();
        return ReceiptMath.money(base.multiply(ewtRate).movePointLeft(2));
    }

    public BigDecimal netTotal() {
        return ReceiptMath.money(grandTotal().subtract(ewtAmount()));
    }

    /** Returns a copy of this receipt with the given attachments. */
    public Receipt withAttachments(List<Attachment> newAttachments) {
        return new Receipt(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                newAttachments, encodedBy, createdAt, modifiedAt, companyId, notes, paymentMethod, ewtRate,
                classification, supplierTaxClassification);
    }

    public Receipt withClassification(String newClassification) {
        return new Receipt(id, receiptNo, date, type, supplierId, projectId, items, discount, vat,
                additionalCharges, attachments, encodedBy, createdAt, modifiedAt, companyId, notes,
                paymentMethod, ewtRate, newClassification, supplierTaxClassification);
    }

    public Receipt withSupplierTaxClassification(String newClassification) {
        return new Receipt(id, receiptNo, date, type, supplierId, projectId, items, discount, vat,
                additionalCharges, attachments, encodedBy, createdAt, modifiedAt, companyId, notes,
                paymentMethod, ewtRate, classification, newClassification);
    }
}
