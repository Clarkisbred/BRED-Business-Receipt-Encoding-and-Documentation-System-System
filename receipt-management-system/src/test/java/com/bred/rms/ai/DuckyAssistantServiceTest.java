package com.bred.rms.ai;

import com.bred.rms.importer.DocumentFileValidator;
import com.bred.rms.importer.DocumentTextExtractor;
import com.bred.rms.config.AppContext;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.services.ProjectService;
import com.bred.rms.services.CurrentUser;
import com.bred.rms.services.ReceiptService;
import com.bred.rms.services.SupplierService;
import com.bred.rms.support.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckyAssistantServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sendsTextAndPriorConversationToInjectedProviderWithoutAttachments() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        DuckyAssistantService service = service(provider);

        assertEquals("Local answer", service.respond("How do I read a receipt?",
                List.of("User: Hello", "Ducky: Hi"), List.of(), false));

        assertFalse(provider.request.hasAttachments());
        assertTrue(provider.request.prompt().contains("User: Hello"));
        assertTrue(provider.request.prompt().contains("User message:\nHow do I read a receipt?"));
        assertTrue(provider.request.prompt().contains("read-only"));
    }

    @Test
    void promptAllowsNaturalConversationAndKeepsMissingRecordFactsScoped() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.answer = "Sure — I'm here and ready to help.";

        assertEquals(provider.answer, service(provider).respond("test",
                List.of("User: hello", "Ducky: Hi there!"), List.of(), false));

        String prompt = provider.request.prompt();
        assertTrue(prompt.contains("Respond naturally to ordinary"));
        assertTrue(prompt.contains("simple tests"));
        assertTrue(prompt.contains("This restriction does not apply to"));
        assertTrue(prompt.contains("User: hello"));
        assertTrue(prompt.contains("User message:\ntest"));
        assertFalse(prompt.contains("If it is absent or lacks the answer"));
    }

    @Test
    void reportsProviderErrorsAndRejectsEmptyModelAnswers() {
        CapturingProvider failedProvider = new CapturingProvider();
        failedProvider.failure = new AIProviderException("Local Ollama is unavailable.");
        AIProviderException failure = assertThrows(AIProviderException.class,
                () -> service(failedProvider).respond("hello", List.of(), List.of(), false));
        assertEquals("Local Ollama is unavailable.", failure.getMessage());

        CapturingProvider emptyProvider = new CapturingProvider();
        emptyProvider.answer = "  ";
        AIProviderException empty = assertThrows(AIProviderException.class,
                () -> service(emptyProvider).respond("hello", List.of(), List.of(), false));
        assertTrue(empty.getMessage().contains("empty answer"));
    }

    @Test
    void attachmentContentIsExtractedLocallyAndMarkedAsAttachmentContext() throws Exception {
        Path receipt = temporaryDirectory.resolve("fictional.csv");
        Files.writeString(receipt, "Item,Quantity,Unit Price\nNotebook,2,25.00\n");
        CapturingProvider provider = new CapturingProvider();

        service(provider).respond("Summarize this receipt.", List.of(), List.of(receipt), false);

        assertTrue(provider.request.hasAttachments());
        assertTrue(provider.request.prompt().contains("Notebook"));
        assertTrue(provider.request.prompt().contains("Local-only extracted document text"));
        assertFalse(provider.request.prompt().contains("data:image"));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(receipt), files.toList());
        }
    }

    @Test
    void rejectsNonLocalOllamaEndpoints() {
        DuckyAssistantService.validateLocalOllamaEndpoint(URI.create("http://localhost:11434"));
        assertThrows(IllegalStateException.class, () -> DuckyAssistantService.validateLocalOllamaEndpoint(
                URI.create("https://api.example.test")));
    }

    @Test
    void enablesRecordContextOnlyForApprovedActiveUsersWithAuthorizedCompanyScope() {
        User approvedCompanyUser = new User(UUID.randomUUID(), "Test User", "user@example.test", Role.ENCODER,
                true, AccountStatus.APPROVED, Instant.EPOCH, "Test Co", UUID.randomUUID());
        User pendingCompanyUser = new User(UUID.randomUUID(), "Pending User", "pending@example.test", Role.ENCODER,
                true, AccountStatus.PENDING, Instant.EPOCH, "Test Co", UUID.randomUUID());
        User missingCompanyUser = new User(UUID.randomUUID(), "No Company", "none@example.test", Role.ADMIN,
                true, AccountStatus.APPROVED, Instant.EPOCH, "Test Co", null);

        assertTrue(service(new CapturingProvider(), approvedCompanyUser).canReadRecords());
        assertFalse(service(new CapturingProvider(), pendingCompanyUser).canReadRecords());
        assertFalse(service(new CapturingProvider(), missingCompanyUser).canReadRecords());
    }

    @Test
    void localRecordContextExcludesReceiptsAndUnassociatedSuppliersFromOtherCompanies() throws Exception {
        UUID companyId = UUID.randomUUID();
        User user = new User(UUID.randomUUID(), "Test User", "user@example.test", Role.ADMIN, true,
                AccountStatus.APPROVED, Instant.EPOCH, "Test Co", companyId);
        Fakes.Receipts receipts = new Fakes.Receipts();
        Fakes.Suppliers suppliers = new Fakes.Suppliers();
        Fakes.Projects projects = new Fakes.Projects();
        Fakes.Attachments attachments = new Fakes.Attachments();
        var ownSupplier = suppliers.add("Own Company Supplier", "TIN-OWN");
        var foreignSupplier = suppliers.add("Other Company Supplier", "TIN-OTHER");
        Receipt own = scoped(Fakes.receipt("OWN-1", ownSupplier, null,
                java.time.LocalDate.of(2026, 1, 2), "Paper", "10"), companyId);
        Receipt foreign = scoped(Fakes.receipt("OTHER-1", foreignSupplier, null,
                java.time.LocalDate.of(2026, 1, 3), "Ink", "20"), UUID.randomUUID());
        receipts.data.put(own.id(), own);
        receipts.data.put(foreign.id(), foreign);
        CurrentUser current = () -> user;
        AppContext context = AppContext.forTesting(user,
                new ReceiptService(receipts, suppliers, projects, attachments, current),
                new SupplierService(suppliers, receipts, current),
                new ProjectService(projects, receipts, current), attachments);
        CapturingProvider provider = new CapturingProvider();
        DuckyAssistantService service = new DuckyAssistantService(context, provider,
                new DocumentFileValidator(), new DocumentTextExtractor((image, temporary) -> "OCR result"),
                temporaryDirectory);

        service.respond("Summarize my authorized records.", List.of(), List.of(), true);

        assertTrue(provider.request.prompt().contains("OWN-1"));
        assertFalse(provider.request.prompt().contains("OTHER-1"));
        assertTrue(provider.request.prompt().contains("Own Company Supplier"));
        assertFalse(provider.request.prompt().contains("Other Company Supplier"));
    }

    private DuckyAssistantService service(CapturingProvider provider) {
        return service(provider, null);
    }

    private DuckyAssistantService service(CapturingProvider provider, User user) {
        AppContext context = user == null ? null : AppContext.forTesting(user, null, null, null, null);
        return new DuckyAssistantService(context, provider, new DocumentFileValidator(),
                new DocumentTextExtractor((image, temporary) -> "OCR result"), temporaryDirectory);
    }

    private static Receipt scoped(Receipt receipt, UUID companyId) {
        return new Receipt(receipt.id(), receipt.receiptNo(), receipt.date(), receipt.type(), receipt.supplierId(),
                receipt.projectId(), receipt.items(), receipt.discount(), receipt.vat(), receipt.additionalCharges(),
                receipt.attachments(), receipt.encodedBy(), receipt.createdAt(), receipt.modifiedAt(), companyId);
    }

    private static final class CapturingProvider implements AIProvider {
        private AIRequest request;
        private String answer = "Local answer";
        private AIProviderException failure;

        @Override public String name() { return "test-local"; }
        @Override public boolean isAvailable() { return true; }
        @Override public String generate(AIRequest request) throws AIProviderException {
            this.request = request;
            if (failure != null) throw failure;
            return answer;
        }
    }
}
