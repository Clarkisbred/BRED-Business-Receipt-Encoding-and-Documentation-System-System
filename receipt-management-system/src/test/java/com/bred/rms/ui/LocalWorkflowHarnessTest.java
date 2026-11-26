package com.bred.rms.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalWorkflowHarnessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresExactlyOneExplicitTemporaryDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalWorkflowHarness.validateTestDirectory(new String[0], temporaryDirectory));
        assertThrows(IllegalArgumentException.class,
                () -> LocalWorkflowHarness.validateTestDirectory(
                        new String[] {temporaryDirectory.resolve("bred-ai-ui-test-a").toString(), "extra"},
                        temporaryDirectory));
    }

    @Test
    void rejectsPathsOutsideTheTemporaryTestDirectoryAndInvalidPathSyntax() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalWorkflowHarness.validateTestDirectory(
                        new String[] {temporaryDirectory.resolve("ordinary-data").toString()}, temporaryDirectory));
        assertThrows(IllegalArgumentException.class,
                () -> LocalWorkflowHarness.validateTestDirectory(
                        new String[] {"bad" + (char) 0 + "path"}, temporaryDirectory));
    }

    @Test
    void rejectsAnExistingTestDirectoryInsteadOfReusingItsData() throws Exception {
        Path existing = Files.createDirectory(temporaryDirectory.resolve("bred-ai-ui-test-existing"));

        assertThrows(IllegalArgumentException.class,
                () -> LocalWorkflowHarness.validateTestDirectory(new String[] {existing.toString()}, temporaryDirectory));
    }

    @Test
    void requiresExplicitOllamaAndDisabledCloudAttachmentConsent() {
        LocalWorkflowHarness.validateLocalAiEnvironment(Map.of(
                "RMS_AI_PROVIDER", "ollama",
                "RMS_CLOUD_AI_ATTACHMENTS_CONSENT", "false"));

        assertThrows(IllegalStateException.class, () -> LocalWorkflowHarness.validateLocalAiEnvironment(Map.of(
                "RMS_AI_PROVIDER", "hybrid",
                "RMS_CLOUD_AI_ATTACHMENTS_CONSENT", "false")));
        assertThrows(IllegalStateException.class, () -> LocalWorkflowHarness.validateLocalAiEnvironment(Map.of(
                "RMS_AI_PROVIDER", "ollama",
                "RMS_CLOUD_AI_ATTACHMENTS_CONSENT", "true")));
    }

    @Test
    void rejectsNonLoopbackOllamaEndpoints() {
        assertThrows(IllegalStateException.class,
                () -> LocalWorkflowHarness.validateLocalAiEndpoint(URI.create("https://ai.example.test")));
    }

    @Test
    void acceptsFreshlyNamedDirectChildOfTemporaryDirectory() throws Exception {
        Path requested = temporaryDirectory.resolve("bred-ai-ui-test-123");

        assertEquals(requested, LocalWorkflowHarness.validateTestDirectory(
                new String[] {requested.toString()}, temporaryDirectory));
    }
}
