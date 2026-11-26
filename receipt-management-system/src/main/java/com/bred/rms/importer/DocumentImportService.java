package com.bred.rms.importer;

import com.bred.rms.ai.AIProvider;
import com.bred.rms.ai.AIProviderException;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Validates, snapshots, reads, and extracts receipt drafts from selected files without saving them. */
public final class DocumentImportService {
    public static final int MAX_FILES_PER_SESSION = 50;
    public static final long MAX_SESSION_BYTES = 100L * 1024 * 1024;
    private final DocumentFileValidator validator;
    private final DocumentTextExtractor textExtractor;
    private final ReceiptExtractionService extractionService;
    private final Path temporaryRoot;

    public DocumentImportService(AIProvider provider, OcrService ocrService) {
        this(provider, ocrService, Path.of(System.getProperty("java.io.tmpdir")),
                new DocumentFileValidator());
    }

    public DocumentImportService(AIProvider provider, OcrService ocrService, Path temporaryRoot,
                                 DocumentFileValidator validator) {
        this.validator = validator;
        this.textExtractor = new DocumentTextExtractor(ocrService);
        this.extractionService = new ReceiptExtractionService(provider);
        this.temporaryRoot = temporaryRoot.toAbsolutePath().normalize();
    }

    public List<ImportedDocument> importFiles(List<Path> files, BiConsumer<Integer, String> progress)
            throws DocumentImportException, InterruptedException {
        if (files == null || files.isEmpty()) return List.of();
        if (files.size() > MAX_FILES_PER_SESSION) {
            throw new DocumentImportException("Select no more than " + MAX_FILES_PER_SESSION + " documents at a time.");
        }
        long totalBytes = 0;
        for (Path file : files) {
            try {
                if (file != null) totalBytes = Math.addExact(totalBytes, Files.size(file));
            } catch (IOException | ArithmeticException | SecurityException e) {
                continue;
            }
        }
        if (totalBytes > MAX_SESSION_BYTES) {
            throw new DocumentImportException("The selected documents exceed the 100 MiB per-session import limit.");
        }

        List<ImportedDocument> results = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            checkInterrupted();
            Path file = files.get(i);
            if (progress != null) {
                String filename = file == null || file.getFileName() == null
                        ? "Selected document" : file.getFileName().toString();
                progress.accept(i + 1, filename);
            }
            results.add(importFile(file));
        }
        return List.copyOf(results);
    }

    public ImportedDocument importFile(Path source) throws InterruptedException {
        UUID documentId = UUID.randomUUID();
        String filename = source == null || source.getFileName() == null
                ? "Unknown document" : source.getFileName().toString();
        String extractedText = "";
        List<String> warnings = new ArrayList<>();
        try {
            DocumentFileType type = validator.validate(source);
            Files.createDirectories(temporaryRoot);
            try (TemporaryWorkspace workspace = TemporaryWorkspace.create(temporaryRoot)) {
                Path snapshot = workspace.path().resolve("source." + type.extension());
                copyWithinLimit(source, snapshot);
                DocumentFileType snapshotType = validator.validate(snapshot);
                if (snapshotType != type) {
                    return failed(documentId, filename, extractedText, warnings,
                            "The selected file changed while it was being imported.");
                }

                ExtractedDocumentText extracted = textExtractor.extract(snapshot, snapshotType, workspace.path());
                extractedText = extracted.text();
                warnings.addAll(extracted.warnings());
                if (!hasUsableText(extracted.text())) {
                    warnings.add("No usable text could be extracted; AI receipt extraction was skipped.");
                    return new ImportedDocument(documentId, filename, extracted.text(), "", warnings, List.of());
                }
                try {
                    List<ReceiptCandidate> receipts = extractionService.extract(
                            documentId, filename, extracted.text());
                    if (receipts.isEmpty()) {
                        warnings.add("The AI did not identify a receipt. Review the extracted text before closing.");
                    }
                    return new ImportedDocument(documentId, filename, extracted.text(), "", warnings, receipts);
                } catch (AIProviderException | DocumentImportException e) {
                    return failed(documentId, filename, extractedText, warnings,
                            "Receipt extraction did not complete: " + safeMessage(e));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (DocumentImportException e) {
            return failed(documentId, filename, extractedText, warnings, e.getMessage());
        } catch (IOException e) {
            return failed(documentId, filename, extractedText, warnings,
                    "The document could not be read or temporary import data could not be cleaned up.");
        } catch (SecurityException e) {
            return failed(documentId, filename, extractedText, warnings,
                    "The document or temporary import workspace could not be accessed.");
        }
    }

    private void copyWithinLimit(Path source, Path target) throws IOException, DocumentImportException {
        long copied = 0;
        try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > validator.maximumBytes()) {
                    throw new DocumentImportException("The selected document exceeded the per-file import limit.");
                }
                output.write(buffer, 0, count);
            }
        }
    }

    private static boolean hasUsableText(String text) {
        String withoutMarkers = text.replaceAll("(?m)^\\[.*]$\\R?", "")
                .replaceAll("(?m)^Page \\d+ \\(.*\\)$\\R?", "").strip();
        return !withoutMarkers.isBlank();
    }

    private static ImportedDocument failed(UUID id, String filename, String text, List<String> warnings,
                                           String error) {
        return new ImportedDocument(id, filename, text, error, warnings, List.of());
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "provider or response error" : error.getMessage();
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Document import cancelled.");
    }

    private static final class TemporaryWorkspace implements AutoCloseable {
        private final Path path;

        private TemporaryWorkspace(Path path) {
            this.path = path;
        }

        static TemporaryWorkspace create(Path root) throws IOException {
            return new TemporaryWorkspace(Files.createTempDirectory(root, "rms-receipt-import-"));
        }

        Path path() {
            return path;
        }

        @Override
        public void close() throws IOException {
            try (var paths = Files.walk(path)) {
                for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }
}
