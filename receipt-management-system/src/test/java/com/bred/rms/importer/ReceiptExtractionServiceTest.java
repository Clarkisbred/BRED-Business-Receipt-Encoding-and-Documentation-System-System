package com.bred.rms.importer;

import com.bred.rms.ai.AIProvider;
import com.bred.rms.ai.AIProviderException;
import com.bred.rms.ai.AIRequest;
import com.bred.rms.ai.HybridAIProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReceiptExtractionServiceTest {
    private static final String TWO_RECEIPTS = """
            {"receipts":[
              {"receipt_date":"2026-11-02","tin":"123-456","receipt_number":"R-1","supplier_name":"Store A",
               "supplier_address":null,"supplier_contact":null,
               "line_items":[{"description":"Paper","quantity":2,"unit":"ream","unit_price":"10.00","line_total":"20.00"}],
               "subtotal":"20.00","tax":"0","discount":null,"grand_total":"20.00","currency":"PHP",
               "source_reference":"Sheet1 rows 2-3","confidence":{"supplier_name":"high"},"review_flags":[]},
              {"receipt_number":"R-2","supplier_name":"Store B","line_items":[],
               "source_reference":"Page 2","review_flags":["Two totals need comparison."]}
            ]}
            """;

    @Test
    void parsesMultipleReceiptsAndPreservesSourceAndConfidence() throws Exception {
        AtomicReference<AIRequest> request = new AtomicReference<>();
        ReceiptExtractionService service = new ReceiptExtractionService(provider(TWO_RECEIPTS, request));
        UUID documentId = UUID.randomUUID();

        List<ReceiptCandidate> candidates = service.extract(documentId, "receipts.xlsx",
                "Worksheet: Sheet1\nReceipt No=R-1");

        assertEquals(2, candidates.size());
        assertEquals("R-1", candidates.get(0).value(ReceiptField.RECEIPT_NUMBER));
        assertEquals("20.00", candidates.get(0).lineItems().get(0).lineTotal());
        assertEquals("Sheet1 rows 2-3", candidates.get(0).sourceReference());
        assertEquals("high", candidates.get(0).confidence(ReceiptField.SUPPLIER_NAME));
        assertEquals(documentId, candidates.get(0).sourceDocumentId());
        assertEquals("receipts.xlsx", candidates.get(1).sourceFilename());
        assertEquals("Two totals need comparison.", candidates.get(1).reviewFlags().get(0));
        assertTrue(request.get().hasAttachments());
        assertTrue(request.get().prompt().contains("Worksheet: Sheet1"));
    }

    @Test
    void parsesJsonCodeFencesAndKeepsMissingValuesVisible() throws Exception {
        ReceiptExtractionService service = new ReceiptExtractionService(provider(
                "```json\n{\"receipts\":[{\"receipt_number\":null,\"supplier_name\":\"null\"}]}\n```", null));
        ReceiptCandidate candidate = service.extract(UUID.randomUUID(), "scan.png", "Receipt text").get(0);

        assertEquals("", candidate.value(ReceiptField.RECEIPT_NUMBER));
        assertEquals("", candidate.value(ReceiptField.SUPPLIER_NAME));
        assertTrue(new ReceiptCandidateValidator().validate(candidate)
                .stream().anyMatch(warning -> warning.contains("Missing receipt number")));
    }

    @Test
    void invalidAiJsonAndUnexpectedShapeBecomeReviewableErrors() {
        ReceiptExtractionService invalidJson = new ReceiptExtractionService(provider("not JSON", null));
        assertThrows(DocumentImportException.class,
                () -> invalidJson.extract(UUID.randomUUID(), "scan.pdf", "text"));

        ReceiptExtractionService wrongShape = new ReceiptExtractionService(provider("{\"receipt\":{}}", null));
        assertThrows(DocumentImportException.class,
                () -> wrongShape.extract(UUID.randomUUID(), "scan.pdf", "text"));
    }

    @Test
    void cancellationDoesNotTriggerCloudFallbackForAnImportedDocument() throws Exception {
        AtomicInteger cloudCalls = new AtomicInteger();
        AIProvider local = new AIProvider() {
            @Override public String name() { return "local"; }
            @Override public boolean isAvailable() { return false; }
            @Override public String generate(AIRequest request) throws AIProviderException {
                Thread.currentThread().interrupt();
                throw new AIProviderException("local request interrupted");
            }
        };
        AIProvider cloud = new AIProvider() {
            @Override public String name() { return "cloud"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String generate(AIRequest request) {
                cloudCalls.incrementAndGet();
                return TWO_RECEIPTS;
            }
        };

        try {
            ReceiptExtractionService service = new ReceiptExtractionService(new HybridAIProvider(local, cloud));
            assertThrows(InterruptedException.class,
                    () -> service.extract(UUID.randomUUID(), "source.pdf", "extracted text"));
            assertEquals(0, cloudCalls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void validatesAmountsAndReportsDiscrepanciesWithoutChangingDraftValues() {
        EnumMap<ReceiptField, String> fields = new EnumMap<>(ReceiptField.class);
        fields.put(ReceiptField.DATE, "03/04/2026");
        fields.put(ReceiptField.RECEIPT_NUMBER, "R-9");
        fields.put(ReceiptField.SUPPLIER_NAME, "Store");
        fields.put(ReceiptField.SUBTOTAL, "30.00");
        fields.put(ReceiptField.TAX, "1.00");
        fields.put(ReceiptField.DISCOUNT, "0.00");
        fields.put(ReceiptField.GRAND_TOTAL, "50.00");
        ReceiptCandidate candidate = new ReceiptCandidate(UUID.randomUUID(), UUID.randomUUID(), "source.csv",
                "Sheet1 row 2", fields, null, List.of(),
                List.of(new ImportedLineItem("Paper", "2", "each", "10.00", "25.00")));

        List<String> warnings = new ReceiptCandidateValidator().validate(candidate);

        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("ambiguous numeric format")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("unit price does not match")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("line totals do not add up")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("grand total")));
        assertEquals("30.00", candidate.value(ReceiptField.SUBTOTAL));
        assertEquals(new BigDecimal("25.00"), new BigDecimal(candidate.lineItems().get(0).lineTotal()));
    }

    @Test
    void flagsMalformedDatesAndNumbersWithoutDiscardingTheirOriginalValues() {
        EnumMap<ReceiptField, String> fields = new EnumMap<>(ReceiptField.class);
        fields.put(ReceiptField.DATE, "not-a-date");
        fields.put(ReceiptField.RECEIPT_NUMBER, "R-4");
        fields.put(ReceiptField.SUPPLIER_NAME, "Store");
        fields.put(ReceiptField.SUBTOTAL, "ten dollars");
        ReceiptCandidate candidate = new ReceiptCandidate(UUID.randomUUID(), UUID.randomUUID(), "scan.png",
                "Page 1", fields, null, null,
                List.of(new ImportedLineItem("Item", "two", "each", "$--", "10.00")));

        List<String> warnings = new ReceiptCandidateValidator().validate(candidate);

        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("date could not be validated")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("quantity is not a valid number")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("unit price is not a valid number")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("subtotal is not a valid number")));
        assertEquals("not-a-date", candidate.value(ReceiptField.DATE));
        assertEquals("two", candidate.lineItems().get(0).quantity());
    }

    @Test
    void leavesOversizedExtractedTextForReviewWithoutCallingAi() {
        AtomicReference<AIRequest> request = new AtomicReference<>();
        ReceiptExtractionService service = new ReceiptExtractionService(provider(TWO_RECEIPTS, request));
        String text = "x".repeat(ReceiptExtractionService.MAX_AI_INPUT_CHARACTERS + 1);

        assertThrows(DocumentImportException.class,
                () -> service.extract(UUID.randomUUID(), "large.csv", text));
        assertNull(request.get());
    }

    @Test
    void importServiceKeepsTextAndCleansTemporaryWorkspaceOnProviderFailure() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("receipt-import-test");
        java.nio.file.Path file = root.resolve("receipts.csv");
        java.nio.file.Files.writeString(file, "Receipt No,Total\nR-1,20.00\n");
        java.nio.file.Path tempRoot = root.resolve("work");
        AIProvider failedProvider = new AIProvider() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return false; }
            @Override public String generate(AIRequest request) throws AIProviderException {
                throw new AIProviderException("provider offline");
            }
        };
        DocumentImportService service = new DocumentImportService(failedProvider,
                (image, workspace) -> "", tempRoot, new DocumentFileValidator());

        ImportedDocument result = service.importFile(file);

        assertTrue(result.extractedText().contains("R-1"));
        assertTrue(result.error().contains("provider offline"));
        try (var entries = java.nio.file.Files.list(tempRoot)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void reviewSessionCanRemoveReceiptsAndDocumentsWithoutPersistence() {
        UUID documentId = UUID.randomUUID();
        ReceiptCandidate candidate = new ReceiptCandidate(UUID.randomUUID(), documentId, "a.csv", "row 2",
                null, null, null, null);
        ImportedDocument document = new ImportedDocument(documentId, "a.csv", "original", "",
                List.of(), List.of(candidate));
        ImportSessionModel session = new ImportSessionModel(List.of(document));

        session.removeReceipt(documentId, candidate.id());
        assertTrue(session.document(documentId).receipts().isEmpty());
        session.removeDocument(documentId);
        assertTrue(session.documents().isEmpty());
    }

    private static AIProvider provider(String response, AtomicReference<AIRequest> captured) {
        return new AIProvider() {
            @Override public String name() { return "test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String generate(AIRequest request) {
                if (captured != null) captured.set(request);
                return response;
            }
        };
    }
}
