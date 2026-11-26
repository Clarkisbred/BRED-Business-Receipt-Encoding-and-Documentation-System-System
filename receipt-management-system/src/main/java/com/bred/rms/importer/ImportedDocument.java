package com.bred.rms.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Path;

/** One document's transient processing result, including original extracted text and review errors. */
public final class ImportedDocument {
    private final UUID id;
    private final String filename;
    private final String extractedText;
    private final String error;
    private final List<String> warnings;
    private final List<ReceiptCandidate> receipts;
    private final Path attachmentSource;

    public ImportedDocument(UUID id, String filename, String extractedText, String error,
                            List<String> warnings, List<ReceiptCandidate> receipts) {
        this(id, filename, extractedText, error, warnings, receipts, null);
    }

    public ImportedDocument(UUID id, String filename, String extractedText, String error,
                            List<String> warnings, List<ReceiptCandidate> receipts, Path attachmentSource) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.filename = filename == null ? "Unknown document" : filename;
        this.extractedText = extractedText == null ? "" : extractedText;
        this.error = error == null ? "" : error;
        this.warnings = new ArrayList<>(warnings == null ? List.of() : warnings);
        this.receipts = new ArrayList<>(receipts == null ? List.of() : receipts);
        this.attachmentSource = attachmentSource;
    }

    public UUID id() { return id; }
    public String filename() { return filename; }
    public String extractedText() { return extractedText; }
    public String error() { return error; }
    public List<String> warnings() { return List.copyOf(warnings); }
    public List<ReceiptCandidate> receipts() { return List.copyOf(receipts); }
    public Path attachmentSource() { return attachmentSource; }

    void removeReceipt(UUID receiptId) {
        receipts.removeIf(receipt -> receipt.id().equals(receiptId));
    }

    void replaceReceipt(ReceiptCandidate candidate) {
        for (int i = 0; i < receipts.size(); i++) {
            if (receipts.get(i).id().equals(candidate.id())) {
                receipts.set(i, candidate);
                return;
            }
        }
        throw new IllegalArgumentException("Receipt candidate is no longer in this import session.");
    }

    @Override
    public String toString() {
        String state = !error.isBlank() ? " — needs attention" : " — " + receipts.size() + " receipt(s)";
        return filename + state;
    }
}
