package com.bred.rms.importer;

import java.util.List;

/** Text extracted from a source document, with explicit notices for pages or content that need review. */
public record ExtractedDocumentText(String text, List<String> warnings) {
    public ExtractedDocumentText {
        text = text == null ? "" : text;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
