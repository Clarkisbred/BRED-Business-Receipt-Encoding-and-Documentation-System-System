package com.bred.rms.ai;

import com.bred.rms.config.AiImportConfig;

/** Creates the provider selected by the AI importer configuration. */
public final class AIProviders {
    private AIProviders() { }

    public static AIProvider create(AiImportConfig config) {
        LocalAIProvider local = new LocalAIProvider(config);
        CloudAIProvider cloud = new CloudAIProvider(config);
        return switch (config.provider()) {
            case "ollama" -> local;
            case "cloud" -> cloud;
            case "hybrid" -> new HybridAIProvider(local, cloud);
            case "disabled" -> new DisabledAIProvider();
            default -> throw new IllegalArgumentException("Unsupported configured AI provider.");
        };
    }
}
