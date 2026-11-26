package com.bred.rms.importer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Executes the configured OCR program; kept injectable so OCR behavior is testable without Tesseract. */
@FunctionalInterface
public interface OcrProcessExecutor {
    ProcessOutput execute(List<String> command, Duration timeout, Path temporaryDirectory)
            throws IOException, InterruptedException;

    record ProcessOutput(int exitCode, String standardOutput) { }
}
