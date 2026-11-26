package com.bred.rms.ai;

import java.util.Map;

/** Resolves development environment credentials before the platform's secure user store. */
public final class GroqCredentials {
    private final Map<String, String> environment;
    private final GroqCredentialStore store;

    public GroqCredentials() {
        this(System.getenv(), null);
    }

    public GroqCredentials(Map<String, String> environment, GroqCredentialStore store) {
        this.environment = Map.copyOf(environment);
        this.store = store;
    }

    public char[] read() {
        String envKey = environment.get("GROQ_API_KEY");
        return envKey == null || envKey.isBlank() ? store().read() : envKey.toCharArray();
    }

    public boolean environmentConfigured() {
        String key = environment.get("GROQ_API_KEY");
        return key != null && !key.isBlank();
    }

    public boolean credentialSaved() {
        if (environmentConfigured()) return false;
        char[] value = store().read();
        if (value == null) return false;
        java.util.Arrays.fill(value, '\0');
        return true;
    }

    public void save(char[] apiKey) {
        if (environmentConfigured()) {
            throw new IllegalStateException("GROQ_API_KEY is set; update the development environment instead.");
        }
        store().save(apiKey);
    }

    public void removeSaved() {
        if (!environmentConfigured()) store().delete();
    }

    private GroqCredentialStore store() {
        return store == null ? GroqCredentialStore.forCurrentPlatform() : store;
    }
}
