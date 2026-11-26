package com.bred.rms.importer;

import java.nio.file.Path;

/** OCR engine abstraction for images rendered from PDFs and supported image files. */
@FunctionalInterface
public interface OcrService {
    String recognize(Path image, Path temporaryDirectory) throws OcrException, InterruptedException;
}
