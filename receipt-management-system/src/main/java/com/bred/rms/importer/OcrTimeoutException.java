package com.bred.rms.importer;

import java.io.IOException;

/** The OCR executable did not finish within the configured timeout. */
public final class OcrTimeoutException extends IOException {
    public OcrTimeoutException() {
        super("The OCR process timed out.");
    }
}
