package com.bred.rms.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiImportConfigTest {
    @Test
    void defaultsToOptionalLocalOllamaWithoutCloudCredentials() {
        AiImportConfig config = AiImportConfig.fromEnvironment(Map.of());

        assertEquals("ollama", config.provider());
        assertEquals(URI.create("http://localhost:11434"), config.ollamaBaseUrl());
        assertEquals("qwen2.5:3b", config.ollamaModel());
        assertFalse(config.cloudApiKey().isPresent());
        assertEquals("tesseract.exe", config.tesseractCommand());
    }

    @Test
    void readsCloudSecretsAndOcrExecutableOnlyFromEnvironment() {
        String secret = "private-test-value";
        AiImportConfig config = AiImportConfig.fromEnvironment(Map.of(
                "RMS_AI_PROVIDER", "cloud",
                "RMS_CLOUD_AI_BASE_URL", "https://api.example.com",
                "RMS_CLOUD_AI_API_KEY", secret,
                "RMS_OLLAMA_MODEL", "qwen2.5:7b",
                "RMS_TESSERACT_EXE", "C:\\Tools\\Tesseract-OCR\\tesseract.exe"));

        assertEquals("cloud", config.provider());
        assertEquals(URI.create("https://api.example.com"), config.cloudBaseUrl().orElseThrow());
        assertEquals(secret, config.cloudApiKey().orElseThrow());
        assertEquals("qwen2.5:7b", config.ollamaModel());
        assertEquals(Path.of("C:\\Tools\\Tesseract-OCR\\tesseract.exe").toAbsolutePath().normalize(),
                config.tesseractExecutable().orElseThrow());
        assertEquals(config.tesseractExecutable().orElseThrow().toString(), config.tesseractCommand());
        assertFalse(config.toString().contains(secret));
    }

    @Test
    void rejectsUnsupportedProvidersAndInsecureCloudEndpoints() {
        assertThrows(IllegalArgumentException.class, () ->
                AiImportConfig.fromEnvironment(Map.of("RMS_AI_PROVIDER", "unknown")));
        assertThrows(IllegalArgumentException.class, () ->
                AiImportConfig.fromEnvironment(Map.of("RMS_CLOUD_AI_BASE_URL", "http://api.example.com")));
        assertThrows(IllegalArgumentException.class, () ->
                AiImportConfig.fromEnvironment(Map.of("RMS_CLOUD_AI_BASE_URL", "https:api.example.com")));
    }
}
