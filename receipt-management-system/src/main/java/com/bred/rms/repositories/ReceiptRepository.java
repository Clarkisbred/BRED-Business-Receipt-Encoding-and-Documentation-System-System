package com.bred.rms.repositories;

import com.bred.rms.models.Receipt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stores receipts with their items and attachment records. */
public interface ReceiptRepository {
    List<Receipt> findAll();

    Optional<Receipt> findById(UUID id);

    /** Inserts or updates (by id) atomically with items and attachment rows; returns the stored receipt. */
    Receipt save(Receipt receipt);

    /**
     * Creates a new AI-imported receipt through a server-side administrator/company authorization gate.
     * Implementations without that server authorization must fail closed.
     */
    default Receipt saveApprovedImport(Receipt receipt) {
        throw new RepositoryException("AI receipt approval requires server-side authorization.", false);
    }

    /** Links attachments to a saved imported receipt through the same server-side approval gate. */
    default Receipt saveApprovedImportAttachments(Receipt receipt) {
        throw new RepositoryException("AI receipt attachment linking requires server-side authorization.", false);
    }

    /** Deletes a receipt (admin only) and returns the Storage paths of its attachments so files can be removed. */
    List<String> delete(UUID id);
}
