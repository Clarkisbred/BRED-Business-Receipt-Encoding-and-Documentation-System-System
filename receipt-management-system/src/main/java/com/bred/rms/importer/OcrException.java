package com.bred.rms.importer;

/** OCR could not produce a trustworthy text result. */
public final class OcrException extends Exception {
    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
