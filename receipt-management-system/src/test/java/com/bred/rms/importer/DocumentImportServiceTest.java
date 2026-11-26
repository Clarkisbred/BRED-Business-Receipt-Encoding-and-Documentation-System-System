package com.bred.rms.importer;

import com.bred.rms.ai.AIProvider;
import com.bred.rms.ai.AIRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentImportServiceTest {
    private static final String ONE_RECEIPT =
            "{\"receipts\":[{\"receipt_number\":\"R-1\",\"supplier_name\":\"Store\"}]}";

    @TempDir
    Path temporaryDirectory;

    @Test
    void processesMultipleFilesAsOneTransientSessionAndReportsProgress() throws Exception {
        Path first = csv("first.csv", "Receipt No,Total\nR-1,15.00\n");
        Path second = csv("second.csv", "Receipt No,Total\nR-2,25.00\n");
        AtomicInteger calls = new AtomicInteger();
        List<String> progress = new ArrayList<>();
        DocumentImportService service = service(request -> {
            calls.incrementAndGet();
            return ONE_RECEIPT;
        });

        List<ImportedDocument> results = service.importFiles(List.of(first, second),
                (index, filename) -> progress.add(index + ":" + filename));

        assertEquals(2, results.size());
        assertEquals(2, calls.get());
        assertEquals(1, results.get(0).receipts().size());
        assertEquals(1, results.get(1).receipts().size());
        assertEquals(List.of("1:first.csv", "2:second.csv"), progress);
        assertTrue(results.get(1).extractedText().contains("R-2"));
        try (var entries = Files.list(temporaryDirectory.resolve("work"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void gracefullyReportsInvalidAndEmptyDocumentsWithoutCallingAiForEmptyInput() throws Exception {
        Path invalid = temporaryDirectory.resolve("not-a-document.exe");
        Files.writeString(invalid, "binary", StandardCharsets.UTF_8);
        Path emptyCsv = csv("empty.csv", "");
        AtomicInteger calls = new AtomicInteger();
        DocumentImportService service = service(request -> {
            calls.incrementAndGet();
            return ONE_RECEIPT;
        });

        List<ImportedDocument> results = service.importFiles(List.of(invalid, emptyCsv), null);

        assertEquals(2, results.size());
        assertFalse(results.get(0).error().isBlank());
        assertTrue(results.get(1).error().isBlank());
        assertTrue(results.get(1).warnings().stream().anyMatch(warning -> warning.contains("No usable text")));
        assertEquals(0, calls.get());
    }

    private DocumentImportService service(java.util.function.Function<AIRequest, String> response) throws Exception {
        AIProvider provider = new AIProvider() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String generate(AIRequest request) { return response.apply(request); }
        };
        Path root = temporaryDirectory.resolve("work");
        return new DocumentImportService(provider, (image, workspace) -> "",
                root, new DocumentFileValidator());
    }

    private Path csv(String name, String content) throws Exception {
        Path file = temporaryDirectory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
