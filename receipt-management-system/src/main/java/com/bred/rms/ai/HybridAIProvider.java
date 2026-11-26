package com.bred.rms.ai;

import java.util.Objects;

/** Tries the local provider first and uses cloud AI only after a local failure. */
public final class HybridAIProvider implements AIProvider {
    private final AIProvider localProvider;
    private final AIProvider cloudProvider;

    public HybridAIProvider(AIProvider localProvider, AIProvider cloudProvider) {
        this.localProvider = Objects.requireNonNull(localProvider);
        this.cloudProvider = Objects.requireNonNull(cloudProvider);
    }

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public boolean isAvailable() {
        return localProvider.isAvailable() || cloudProvider.isAvailable();
    }

    @Override
    public String generate(AIRequest request) throws AIProviderException {
        try {
            return localProvider.generate(request);
        } catch (AIProviderException localFailure) {
            try {
                return cloudProvider.generate(request);
            } catch (AIProviderException cloudFailure) {
                AIProviderException failure = new AIProviderException(
                        "Local and cloud AI providers both failed.", cloudFailure);
                failure.addSuppressed(localFailure);
                throw failure;
            }
        }
    }
}
