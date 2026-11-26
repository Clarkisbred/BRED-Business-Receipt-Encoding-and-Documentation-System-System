package com.bred.rms.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mutable, session-only review state. It has no database or attachment-store dependencies. */
public final class ImportSessionModel {
    private final List<ImportedDocument> documents = new ArrayList<>();

    public ImportSessionModel(List<ImportedDocument> initialDocuments) {
        if (initialDocuments != null) documents.addAll(initialDocuments);
    }

    public List<ImportedDocument> documents() {
        return List.copyOf(documents);
    }

    public void addDocument(ImportedDocument document) {
        documents.add(document);
    }

    public ImportedDocument document(UUID documentId) {
        return documents.stream().filter(document -> document.id().equals(documentId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Document is no longer in this import session."));
    }

    public void removeDocument(UUID documentId) {
        documents.removeIf(document -> document.id().equals(documentId));
    }

    public void removeReceipt(UUID documentId, UUID receiptId) {
        document(documentId).removeReceipt(receiptId);
    }

    public void replaceReceipt(ReceiptCandidate candidate) {
        document(candidate.sourceDocumentId()).replaceReceipt(candidate);
    }
}
