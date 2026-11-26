package com.bred.rms.ai;

/** Provider representing an explicitly disabled AI importer. */
public final class DisabledAIProvider implements AIProvider {
    @Override
    public String name() {
        return "disabled";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String generate(AIRequest request) throws AIProviderException {
        throw new AIProviderException("AI provider is disabled.");
    }
}
