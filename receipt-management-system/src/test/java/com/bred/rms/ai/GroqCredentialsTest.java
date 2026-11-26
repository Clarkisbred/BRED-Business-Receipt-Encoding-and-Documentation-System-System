package com.bred.rms.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroqCredentialsTest {
    @Test
    void environmentKeyTakesPrecedenceAndIsNeverWrittenToTheSecureStore() {
        MemoryStore store = new MemoryStore();
        store.save("saved-key".toCharArray());
        GroqCredentials credentials = new GroqCredentials(Map.of("GROQ_API_KEY", "environment-key"), store);

        assertArrayEquals("environment-key".toCharArray(), credentials.read());
        assertTrue(credentials.environmentConfigured());
        assertThrows(IllegalStateException.class, () -> credentials.save("new-key".toCharArray()));
        credentials.removeSaved();
        assertArrayEquals("saved-key".toCharArray(), store.read());
    }

    @Test
    void securelySavedKeyCanBeReadAndRemovedWithoutPlaintextConfiguration() {
        MemoryStore store = new MemoryStore();
        GroqCredentials credentials = new GroqCredentials(Map.of(), store);
        char[] key = "test-only-key".toCharArray();

        credentials.save(key);

        assertArrayEquals(key, credentials.read());
        assertTrue(credentials.credentialSaved());
        credentials.removeSaved();
        assertArrayEquals(null, credentials.read());
    }

    private static final class MemoryStore implements GroqCredentialStore {
        private char[] secret;

        @Override public char[] read() { return secret == null ? null : secret.clone(); }

        @Override public void save(char[] apiKey) {
            delete();
            secret = apiKey.clone();
        }

        @Override public void delete() {
            if (secret != null) Arrays.fill(secret, '\0');
            secret = null;
        }
    }
}
