package com.bred.rms.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Invokes the separately installed Tesseract executable; Tess4J native libraries are not loaded. */
public final class TesseractOcrService implements OcrService {
    private final String executable;
    private final Duration timeout;
    private final OcrProcessExecutor processExecutor;

    public TesseractOcrService(String executable, Duration timeout) {
        this(executable, timeout, new ProcessBuilderExecutor());
    }

    public TesseractOcrService(String executable, Duration timeout, OcrProcessExecutor processExecutor) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("The Tesseract executable must be configured.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("The OCR timeout must be positive.");
        }
        this.executable = executable;
        this.timeout = timeout;
        this.processExecutor = processExecutor;
    }

    @Override
    public String recognize(Path image, Path temporaryDirectory) throws OcrException, InterruptedException {
        List<String> command = List.of(executable, image.toAbsolutePath().toString(), "stdout", "-l", "eng");
        try {
            OcrProcessExecutor.ProcessOutput output = processExecutor.execute(command, timeout, temporaryDirectory);
            if (output.exitCode() != 0) {
                throw new OcrException("Tesseract exited with status " + output.exitCode() + ".");
            }
            return output.standardOutput();
        } catch (OcrTimeoutException e) {
            throw new OcrException("Tesseract OCR timed out.", e);
        } catch (IOException | SecurityException e) {
            throw new OcrException("Tesseract could not be started or read the image.", e);
        }
    }

    private static final class ProcessBuilderExecutor implements OcrProcessExecutor {
        @Override
        public ProcessOutput execute(List<String> command, Duration timeout, Path temporaryDirectory)
                throws IOException, InterruptedException {
            Path processDirectory = Files.createTempDirectory(temporaryDirectory, "tesseract-");
            Path stdout = processDirectory.resolve("stdout.txt");
            Path stderr = processDirectory.resolve("stderr.txt");
            Process process = null;
            try {
                process = new ProcessBuilder(command)
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile())
                        .start();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                    throw new OcrTimeoutException();
                }
                String result = Files.exists(stdout) ? Files.readString(stdout, StandardCharsets.UTF_8) : "";
                return new ProcessOutput(process.exitValue(), result);
            } catch (InterruptedException e) {
                if (process != null) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                Thread.currentThread().interrupt();
                throw e;
            } finally {
                deleteWorkspace(processDirectory);
            }
        }

        private static void deleteWorkspace(Path directory) throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
