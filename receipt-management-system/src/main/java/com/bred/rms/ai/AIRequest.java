package com.bred.rms.ai;

/** A text-only request to an AI provider. Attachments are not transmitted by this API. */
public record AIRequest(String prompt, boolean hasAttachments) {
    public AIRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("AI prompt must not be blank.");
        }
    }
}
