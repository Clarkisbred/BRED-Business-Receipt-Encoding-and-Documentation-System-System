package com.bred.rms.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Optional AI importer settings. Secrets are read only from the process environment. */
public final class AiImportConfig {
    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:3b";

    private final String provider;
    private final URI ollamaBaseUrl;
    private final String ollamaModel;
    private final Optional<URI> cloudBaseUrl;
    private final Optional<String> cloudApiKey;
    private final Optional<Path> tesseractExecutable;

    private AiImportConfig(String provider, URI ollamaBaseUrl, String ollamaModel,
                           Optional<URI> cloudBaseUrl, Optional<String> cloudApiKey,
                           Optional<Path> tesseractExecutable) {
        this.provider = provider;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaModel = ollamaModel;
        this.cloudBaseUrl = cloudBaseUrl;
        this.cloudApiKey = cloudApiKey;
        this.tesseractExecutable = tesseractExecutable;
    }

    public static AiImportConfig load() {
        return fromEnvironment(System.getenv());
    }

    static AiImportConfig fromEnvironment(Map<String, String> environment) {
        String provider = value(environment, "RMS_AI_PROVIDER", "ollama").toLowerCase(Locale.ROOT);
        if (!provider.equals("ollama") && !provider.equals("cloud") && !provider.equals("disabled")) {
            throw new IllegalArgumentException("RMS_AI_PROVIDER must be ollama, cloud, or disabled.");
        }

        URI ollamaBaseUrl = endpoint(value(environment, "RMS_OLLAMA_BASE_URL", DEFAULT_OLLAMA_URL),
                "RMS_OLLAMA_BASE_URL", false);
        String ollamaModel = value(environment, "RMS_OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);
        Optional<URI> cloudBaseUrl = optionalValue(environment, "RMS_CLOUD_AI_BASE_URL")
                .map(url -> endpoint(url, "RMS_CLOUD_AI_BASE_URL", true));
        Optional<String> cloudApiKey = optionalValue(environment, "RMS_CLOUD_AI_API_KEY");
        Optional<Path> tesseractExecutable = optionalValue(environment, "RMS_TESSERACT_EXE")
                .map(Path::of).map(path -> path.toAbsolutePath().normalize());
        return new AiImportConfig(provider, ollamaBaseUrl, ollamaModel, cloudBaseUrl, cloudApiKey,
                tesseractExecutable);
    }

    private static URI endpoint(String value, String name, boolean httpsOnly) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (httpsOnly && !"https".equalsIgnoreCase(scheme))
                || (!httpsOnly && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(name + " must be a valid " + (httpsOnly ? "HTTPS" : "HTTP(S)")
                    + " endpoint without embedded credentials.");
        }
        return uri;
    }

    private static String value(Map<String, String> environment, String name, String fallback) {
        return optionalValue(environment, name).orElse(fallback);
    }

    private static Optional<String> optionalValue(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    public String provider() { return provider; }
    public URI ollamaBaseUrl() { return ollamaBaseUrl; }
    public String ollamaModel() { return ollamaModel; }
    public Optional<URI> cloudBaseUrl() { return cloudBaseUrl; }
    public Optional<String> cloudApiKey() { return cloudApiKey; }
    public Optional<Path> tesseractExecutable() { return tesseractExecutable; }
    public String tesseractCommand() { return tesseractExecutable.map(Path::toString).orElse("tesseract.exe"); }
}
