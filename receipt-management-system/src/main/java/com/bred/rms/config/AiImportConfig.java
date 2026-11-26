package com.bred.rms.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Optional AI importer settings. Secrets are read only from the process environment. */
public final class AiImportConfig {
    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen2.5:3b";
    private static final String DEFAULT_CLOUD_MODEL = "gpt-4o-mini";
    private static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;

    private final String provider;
    private final URI ollamaBaseUrl;
    private final String ollamaModel;
    private final Optional<URI> cloudBaseUrl;
    private final Optional<String> cloudApiKey;
    private final String cloudModel;
    private final Duration requestTimeout;
    private final boolean cloudAttachmentsConsent;
    private final Optional<Path> tesseractExecutable;

    private AiImportConfig(String provider, URI ollamaBaseUrl, String ollamaModel,
                           Optional<URI> cloudBaseUrl, Optional<String> cloudApiKey,
                           String cloudModel, Duration requestTimeout, boolean cloudAttachmentsConsent,
                           Optional<Path> tesseractExecutable) {
        this.provider = provider;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaModel = ollamaModel;
        this.cloudBaseUrl = cloudBaseUrl;
        this.cloudApiKey = cloudApiKey;
        this.cloudModel = cloudModel;
        this.requestTimeout = requestTimeout;
        this.cloudAttachmentsConsent = cloudAttachmentsConsent;
        this.tesseractExecutable = tesseractExecutable;
    }

    public static AiImportConfig load() {
        return fromEnvironment(System.getenv());
    }

    static AiImportConfig fromEnvironment(Map<String, String> environment) {
        String provider = value(environment, "RMS_AI_PROVIDER", "ollama").toLowerCase(Locale.ROOT);
        if (!provider.equals("ollama") && !provider.equals("cloud") && !provider.equals("hybrid")
                && !provider.equals("disabled")) {
            throw new IllegalArgumentException("RMS_AI_PROVIDER must be ollama, cloud, hybrid, or disabled.");
        }

        URI ollamaBaseUrl = endpoint(value(environment, "RMS_OLLAMA_BASE_URL", DEFAULT_OLLAMA_URL),
                "RMS_OLLAMA_BASE_URL", false);
        String ollamaModel = value(environment, "RMS_OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL);
        Optional<URI> cloudBaseUrl = optionalValue(environment, "RMS_CLOUD_AI_BASE_URL")
                .map(url -> endpoint(url, "RMS_CLOUD_AI_BASE_URL", true));
        Optional<String> cloudApiKey = optionalValue(environment, "RMS_CLOUD_AI_API_KEY");
        String cloudModel = value(environment, "RMS_CLOUD_AI_MODEL", DEFAULT_CLOUD_MODEL);
        Duration requestTimeout = Duration.ofSeconds(positiveLong(
                value(environment, "RMS_AI_REQUEST_TIMEOUT_SECONDS",
                        Long.toString(DEFAULT_REQUEST_TIMEOUT_SECONDS)),
                "RMS_AI_REQUEST_TIMEOUT_SECONDS"));
        boolean cloudAttachmentsConsent = Boolean.parseBoolean(
                value(environment, "RMS_CLOUD_AI_ATTACHMENTS_CONSENT", "false"));
        Optional<Path> tesseractExecutable = optionalValue(environment, "RMS_TESSERACT_EXE")
                .map(Path::of).map(path -> path.toAbsolutePath().normalize());
        return new AiImportConfig(provider, ollamaBaseUrl, ollamaModel, cloudBaseUrl, cloudApiKey,
                cloudModel, requestTimeout, cloudAttachmentsConsent, tesseractExecutable);
    }

    private static long positiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Report the setting with the same actionable validation message as non-positive values.
        }
        throw new IllegalArgumentException(name + " must be a positive number of seconds.");
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
    public String cloudModel() { return cloudModel; }
    public Duration requestTimeout() { return requestTimeout; }
    public boolean cloudAttachmentsConsent() { return cloudAttachmentsConsent; }
    public Optional<Path> tesseractExecutable() { return tesseractExecutable; }
    public String tesseractCommand() { return tesseractExecutable.map(Path::toString).orElse("tesseract.exe"); }
}
