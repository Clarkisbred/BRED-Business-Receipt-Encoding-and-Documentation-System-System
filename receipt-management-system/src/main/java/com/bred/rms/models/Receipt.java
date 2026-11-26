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
                      UUID companyId) {
    public Receipt {
        items = items == null ? List.of() : List.copyOf(items);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        discount = discount == null ? BigDecimal.ZERO : discount;
        vat = vat == null ? BigDecimal.ZERO : vat;
        additionalCharges = additionalCharges == null ? BigDecimal.ZERO : additionalCharges;
    }

    /** Compatibility constructor for callers without persisted company scope. */
    public Receipt(UUID id, String receiptNo, LocalDate date, ReceiptType type, UUID supplierId, UUID projectId,
                   List<ReceiptItem> items, BigDecimal discount, BigDecimal vat, BigDecimal additionalCharges,
                   List<Attachment> attachments, UUID encodedBy, Instant createdAt, Instant modifiedAt) {
        this(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                attachments, encodedBy, createdAt, modifiedAt, null);
    }

    /** Returns the sum of all item subtotals. */
    public BigDecimal subtotal() {
        return ReceiptMath.money(items.stream().map(ReceiptItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Returns subtotal − discount + VAT + additional charges. */
    public BigDecimal grandTotal() {
        return ReceiptMath.money(subtotal().subtract(discount).add(vat).add(additionalCharges));
    }

    /** Returns a copy of this receipt with the given attachments. */
    public Receipt withAttachments(List<Attachment> newAttachments) {
        return new Receipt(id, receiptNo, date, type, supplierId, projectId, items, discount, vat, additionalCharges,
                newAttachments, encodedBy, createdAt, modifiedAt, companyId);
    }
}
