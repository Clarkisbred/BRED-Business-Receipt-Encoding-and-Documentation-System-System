package com.bred.rms.repositories;

import java.util.UUID;

/** The server confirmed the write, but the client could not reload its saved row. */
public final class SavedReceiptReadbackException extends RepositoryException {
    private final UUID receiptId;

    public SavedReceiptReadbackException(UUID receiptId, RuntimeException cause) {
        super("The receipt was saved, but its saved details could not be reloaded.", false);
        this.receiptId = receiptId;
        initCause(cause);
    }

    public UUID receiptId() { return receiptId; }
}
