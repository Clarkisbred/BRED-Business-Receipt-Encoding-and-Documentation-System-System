package com.bred.rms.importer;

import java.util.Locale;
import java.util.Optional;

/** Supported on-disk document formats for receipt import. */
public enum DocumentFileType {
    XLSX("xlsx"), XLS("xls"), CSV("csv"), PDF("pdf"),
    PNG("png"), JPG("jpg"), JPEG("jpeg"), BMP("bmp"), TIF("tif"), TIFF("tiff");

    private final String extension;

    DocumentFileType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static Optional<DocumentFileType> fromFilename(String filename) {
        if (filename == null) return Optional.empty();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return Optional.empty();
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (DocumentFileType type : values()) {
            if (type.extension.equals(extension)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
