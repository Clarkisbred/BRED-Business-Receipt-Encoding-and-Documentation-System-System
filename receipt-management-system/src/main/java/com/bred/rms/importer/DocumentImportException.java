package com.bred.rms.importer;

/** A selected document could not be safely validated or read. */
public final class DocumentImportException extends Exception {
    public DocumentImportException(String message) {
        super(message);
    }

    public DocumentImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
