package com.bred.rms.ai;

/** Common contract for providers that accept text prompts and return generated text. */
public interface AIProvider {
    String name();

    boolean isAvailable();

    String generate(AIRequest request) throws AIProviderException;
}
