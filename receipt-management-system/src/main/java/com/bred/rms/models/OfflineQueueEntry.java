package com.bred.rms.models;

import java.util.UUID;

/** An offline record that may be synchronized only once. */
public record OfflineQueueEntry(
        UUID id,
        String idempotencyKey,
        String entityType,
        String payload,
        String status,
        int syncAttempts
) {
    public OfflineQueueEntry {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("Idempotency key is required.");
        if (entityType == null || entityType.isBlank())
            throw new IllegalArgumentException("Entity type is required.");
        if (payload == null || payload.isBlank())
            throw new IllegalArgumentException("Offline payload is required.");
        if (syncAttempts < 0)
            throw new IllegalArgumentException("Sync attempts cannot be negative.");
    }
}
