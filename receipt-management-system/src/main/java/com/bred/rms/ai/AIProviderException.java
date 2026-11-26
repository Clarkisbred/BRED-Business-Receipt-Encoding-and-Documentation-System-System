package com.bred.rms.ai;

/** A provider request failed or the provider is not configured for the requested operation. */
public final class AIProviderException extends Exception {
    public AIProviderException(String message) {
        super(message);
    }

    public AIProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
