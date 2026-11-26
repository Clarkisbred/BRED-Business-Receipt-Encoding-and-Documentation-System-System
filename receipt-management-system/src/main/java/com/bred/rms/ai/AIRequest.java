package com.bred.rms.ai;

/**
 * A text-only request to an AI provider. {@code hasAttachments} marks source-document context for cloud consent
 * checks; this request type never carries or transmits attachment bytes.
 */
public record AIRequest(String prompt, boolean hasAttachments) {
    public AIRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("AI prompt must not be blank.");
        }
    }
}
