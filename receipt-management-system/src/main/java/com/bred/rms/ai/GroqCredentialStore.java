package com.bred.rms.ai;

/** Per-user secure storage for the Groq API key. */
public interface GroqCredentialStore {
    char[] read();

    void save(char[] apiKey);

    void delete();

    static GroqCredentialStore forCurrentPlatform() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? new WindowsCredentialStore() : new EnvironmentCredentialStore();
    }

    /** Development-only source: no key is written to disk by this implementation. */
    final class EnvironmentCredentialStore implements GroqCredentialStore {
        @Override public char[] read() {
            String value = System.getenv("GROQ_API_KEY");
            return value == null || value.isBlank() ? null : value.toCharArray();
        }

        @Override public void save(char[] apiKey) {
            throw new IllegalStateException("Saved Groq credentials require Windows Credential Manager.");
        }

        @Override public void delete() { }
    }
}
