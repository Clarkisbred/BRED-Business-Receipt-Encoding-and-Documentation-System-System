package com.bred.rms.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesseractOcrServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesConfiguredExecutableWithEnglishAndReturnsMockedOutput() throws Exception {
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();
        Path image = temporaryDirectory.resolve("receipt with spaces.png");
        TesseractOcrService service = new TesseractOcrService("C:\\Tools\\Tesseract\\tesseract.exe",
                Duration.ofSeconds(9), (arguments, limit, workspace) -> {
                    command.set(arguments);
                    timeout.set(limit);
                    return new OcrProcessExecutor.ProcessOutput(0, "Recognized receipt text");
                });

        assertEquals("Recognized receipt text", service.recognize(image, temporaryDirectory));
        assertEquals("C:\\Tools\\Tesseract\\tesseract.exe", command.get().get(0));
        assertEquals(image.toAbsolutePath().toString(), command.get().get(1));
        assertTrue(command.get().contains("stdout"));
        assertTrue(command.get().contains("-l"));
        assertTrue(command.get().contains("eng"));
        assertEquals(Duration.ofSeconds(9), timeout.get());
    }

    @Test
    void reportsExecutableFailuresAndTimeouts() {
        TesseractOcrService exitFailure = new TesseractOcrService("tesseract.exe", Duration.ofSeconds(2),
                (arguments, timeout, workspace) -> new OcrProcessExecutor.ProcessOutput(2, ""));
        assertThrows(OcrException.class,
                () -> exitFailure.recognize(Path.of("receipt.png"), temporaryDirectory));

        TesseractOcrService timeoutFailure = new TesseractOcrService("tesseract.exe", Duration.ofSeconds(2),
                (arguments, timeout, workspace) -> { throw new OcrTimeoutException(); });
        OcrException error = assertThrows(OcrException.class,
                () -> timeoutFailure.recognize(Path.of("receipt.png"), temporaryDirectory));
        assertTrue(error.getMessage().contains("timed out"));
    }

    @Test
    void translatesProcessStartErrorsWithoutLeakingSensitiveOutput() {
        TesseractOcrService service = new TesseractOcrService("tesseract.exe", Duration.ofSeconds(2),
                (arguments, timeout, workspace) -> { throw new IOException("private process detail"); });
        OcrException error = assertThrows(OcrException.class,
                () -> service.recognize(Path.of("receipt.png"), temporaryDirectory));
        assertFalse(error.getMessage().contains("private process detail"));
    }
}
