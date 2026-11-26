package com.bred.rms.ui;

import com.bred.rms.config.AiImportConfig;
import com.bred.rms.config.AppContext;
import com.bred.rms.local.LocalDatabase;
import com.bred.rms.models.Supplier;
import com.bred.rms.ui.theme.ThemeManager;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.net.URI;

/** Test-only launcher for the isolated BRED Swing import workflow. */
public final class LocalWorkflowHarness {
    private static final String TEST_DIRECTORY_PREFIX = "bred-ai-ui-test-";

    private LocalWorkflowHarness() { }

    public static void main(String[] args) {
        Path logFile = null;
        try {
            Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            Path testDirectory = validateTestDirectory(args, tempRoot);
            Files.createDirectory(testDirectory);
            logFile = testDirectory.resolve("harness.log");
            Files.writeString(logFile, logLine("Starting isolated BRED UI test."), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);

            validateLocalAiEnvironment(System.getenv());
            AiImportConfig aiConfig = AiImportConfig.load();
            if (!"ollama".equals(aiConfig.provider()) || aiConfig.cloudAttachmentsConsent()) {
                throw new IllegalStateException("AI configuration must select Ollama with cloud attachment consent disabled.");
            }
            validateLocalAiEndpoint(aiConfig.ollamaBaseUrl());
            appendLog(logFile, "AI provider=ollama; cloud attachment consent=false; endpoint="
                    + aiConfig.ollamaBaseUrl() + "; model=" + aiConfig.ollamaModel() + ".");

            Path localData = testDirectory.resolve("local-data");
            AppContext context = AppContext.createLocal(localData);
            context.auth.login(LocalDatabase.DEFAULT_ADMIN_EMAIL, "admin123".toCharArray());
            context.suppliers.save(new Supplier(null, "Fictional Sample Supply Co.", "000-000-0000",
                    "100 Demo Lane, Test City", "000-0000"));
            appendLog(logFile, "Created local-only context and seeded fictional supplier.");

            Path startupLog = logFile;
            SwingUtilities.invokeLater(() -> displayWindow(context, startupLog));
        } catch (Exception error) {
            if (logFile != null) {
                try {
                    appendLog(logFile, "Startup failed.", error);
                } catch (IOException loggingError) {
                    error.addSuppressed(loggingError);
                }
            }
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static Path validateTestDirectory(String[] args, Path tempRoot) throws IOException {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("Pass exactly one new temporary directory path.");
        }

        Path requested;
        try {
            requested = Path.of(args[0]).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("The temporary directory path is invalid.", error);
        }
        Path canonicalTempRoot = tempRoot.toRealPath();
        String name = requested.getFileName() == null ? "" : requested.getFileName().toString();
        if (!canonicalTempRoot.equals(requested.getParent())
                || !name.startsWith(TEST_DIRECTORY_PREFIX)
                || name.length() == TEST_DIRECTORY_PREFIX.length()) {
            throw new IllegalArgumentException(
                    "The test directory must be a new direct child of the system temp directory named "
                            + TEST_DIRECTORY_PREFIX + "<unique-id>.");
        }
        if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The test directory already exists; choose a fresh unique path.");
        }
        return requested;
    }

    static void validateLocalAiEnvironment(Map<String, String> environment) {
        String provider = environment.getOrDefault("RMS_AI_PROVIDER", "").trim();
        String consent = environment.getOrDefault("RMS_CLOUD_AI_ATTACHMENTS_CONSENT", "").trim();
        if (!"ollama".equalsIgnoreCase(provider) || !"false".equalsIgnoreCase(consent)) {
            throw new IllegalStateException(
                    "Set RMS_AI_PROVIDER=ollama and RMS_CLOUD_AI_ATTACHMENTS_CONSENT=false before starting this test harness.");
        }
    }

    static void validateLocalAiEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null || !(host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"))) {
            throw new IllegalStateException("The Ollama endpoint must use localhost or a loopback IP address.");
        }
    }

    private static void displayWindow(AppContext context, Path logFile) {
        try {
            ThemeManager.installSaved();
            JFrame frame = new JFrame("BRED — Isolated Local Workflow Test");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1180, 760);
            frame.setLocationRelativeTo(null);
            frame.setContentPane(new Shell(context, () -> { }));
            frame.setVisible(true);
            appendLog(logFile, "BRED Swing shell is visible.");
        } catch (Exception error) {
            try {
                appendLog(logFile, "Window initialization failed.", error);
            } catch (IOException loggingError) {
                error.addSuppressed(loggingError);
            }
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void appendLog(Path logFile, String message) throws IOException {
        Files.writeString(logFile, logLine(message), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void appendLog(Path logFile, String message, Exception error) throws IOException {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        Files.writeString(logFile, logLine(message) + trace, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String logLine(String message) {
        return Instant.now() + " " + message + System.lineSeparator();
    }
}
