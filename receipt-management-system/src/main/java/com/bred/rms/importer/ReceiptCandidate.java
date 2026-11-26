package com.bred.rms.importer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory receipt draft produced by extraction. It is intentionally not a persisted Receipt
 * and contains no supplier, company, project, or database identifiers.
 */
public final class ReceiptCandidate {
    private final UUID id;
    private final UUID sourceDocumentId;
    private final String sourceFilename;
    private final String sourceReference;
    private final EnumMap<ReceiptField, String> fields;
    private final EnumMap<ReceiptField, String> confidence;
    private final List<String> reviewFlags;
    private final List<ImportedLineItem> lineItems;

    public ReceiptCandidate(UUID id, UUID sourceDocumentId, String sourceFilename, String sourceReference,
                            Map<ReceiptField, String> fields, Map<ReceiptField, String> confidence,
                            List<String> reviewFlags, List<ImportedLineItem> lineItems) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.sourceDocumentId = sourceDocumentId;
        this.sourceFilename = sourceFilename == null ? "" : sourceFilename;
        this.sourceReference = sourceReference == null || sourceReference.isBlank()
                ? "Source location not identified — verify against extracted text" : sourceReference;
        this.fields = new EnumMap<>(ReceiptField.class);
        for (ReceiptField field : ReceiptField.values()) {
            this.fields.put(field, text(fields == null ? null : fields.get(field)));
        }
        this.confidence = new EnumMap<>(ReceiptField.class);
        if (confidence != null) confidence.forEach((field, value) -> this.confidence.put(field, text(value)));
        this.reviewFlags = new ArrayList<>(reviewFlags == null ? List.of() : reviewFlags);
        this.lineItems = new ArrayList<>();
        if (lineItems != null) lineItems.forEach(item -> this.lineItems.add(item.copy()));
    }

    public UUID id() { return id; }
    public UUID sourceDocumentId() { return sourceDocumentId; }
    public String sourceFilename() { return sourceFilename; }
    public String sourceReference() { return sourceReference; }
    public String value(ReceiptField field) { return fields.get(field); }
    public void setValue(ReceiptField field, String value) { fields.put(field, text(value)); }
    public String confidence(ReceiptField field) { return confidence.getOrDefault(field, ""); }
    public Map<ReceiptField, String> confidenceValues() { return Map.copyOf(confidence); }
    public List<String> reviewFlags() { return List.copyOf(reviewFlags); }
    public List<ImportedLineItem> lineItems() { return lineItems; }

    public ReceiptCandidate copy() {
        return new ReceiptCandidate(id, sourceDocumentId, sourceFilename, sourceReference, fields,
                confidence, reviewFlags, lineItems);
    }

    @Override
    public String toString() {
        String number = value(ReceiptField.RECEIPT_NUMBER);
        String supplier = value(ReceiptField.SUPPLIER_NAME);
        String title = !number.isBlank() ? number : !supplier.isBlank() ? supplier : "Receipt needs identification";
        return title + " — " + sourceReference;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
