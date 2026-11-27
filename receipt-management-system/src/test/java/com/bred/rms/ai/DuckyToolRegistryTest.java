package com.bred.rms.ai;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.repositories.BugReportRepository;
import com.bred.rms.security.Module;
import com.bred.rms.services.ProjectService;
import com.bred.rms.services.BugReportService;
import com.bred.rms.services.CurrentUser;
import com.bred.rms.services.ReceiptService;
import com.bred.rms.services.SupplierService;
import com.bred.rms.support.Fakes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckyToolRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void searchesOnlyBriefAuthorizedRecordsAndRequiresDisclosureForResults() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        var supplier = fixture.suppliers.add("Northwind Office", "TIN-PRIVATE-123");
        supplier = new com.bred.rms.models.Supplier(supplier.id(), supplier.businessName(), supplier.tin(),
                "Private address", "Private phone");
        fixture.suppliers.data.put(supplier.id(), supplier);
        var receipt = scoped(Fakes.receipt("RCPT-SECRET-7", supplier, null,
                LocalDate.of(2026, 1, 2), "Paper", "42.50"), fixture.user.companyId());
        fixture.receipts.data.put(receipt.id(), receipt);
        DuckyToolRegistry registry = fixture.registry();

        var result = registry.execute("searchReceipts", JSON.readTree("{\"query\":\"RCPT-SECRET-7\"}"),
                DuckyPermission.ASSIST);

        assertTrue(result.requiresDisclosure());
        assertTrue(result.content().contains("RCPT-SECRET-7"));
        assertFalse(result.content().contains("TIN-PRIVATE-123"));
        assertFalse(result.content().contains("Private address"));
        assertEquals(15, registry.definitions().size());
    }

    @Test
    void validatesArgumentsAndRejectsRecordsForUnapprovedOrUnscopedUsers() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        DuckyToolRegistry registry = fixture.registry();
        assertThrows(IllegalArgumentException.class, () -> registry.execute("searchReceipts",
                JSON.readTree("{\"query\":\"x\",\"unexpected\":true}"), DuckyPermission.ASSIST));
        assertThrows(IllegalArgumentException.class, () -> registry.execute("getRecentReceipts",
                JSON.readTree("{\"limit\":11}"), DuckyPermission.ASSIST));
        assertThrows(IllegalArgumentException.class, () -> registry.execute("calculateReceiptTotals",
                JSON.readTree("{\"dateFrom\":\"2026-02-01\",\"dateTo\":\"2026-01-01\"}"),
                DuckyPermission.ASSIST));
        assertThrows(IllegalStateException.class, () -> registry.execute("getRecentReceipts",
                JSON.readTree("{}"), DuckyPermission.ASK));

        DuckyToolRegistry pendingRegistry = new Fixture(new User(UUID.randomUUID(), "Pending", "p@example.test",
                Role.ADMIN, true, AccountStatus.PENDING, Instant.EPOCH, "Test Co", UUID.randomUUID())).registry();
        assertThrows(SecurityException.class, () -> pendingRegistry.execute("getRecentReceipts",
                JSON.readTree("{}"), DuckyPermission.ASSIST));

        DuckyToolRegistry unscopedRegistry = new Fixture(new User(UUID.randomUUID(), "No company", "n@example.test",
                Role.ADMIN, true, AccountStatus.APPROVED, Instant.EPOCH, "", null)).registry();
        assertThrows(SecurityException.class, () -> unscopedRegistry.execute("getRecentReceipts",
                JSON.readTree("{}"), DuckyPermission.ASSIST));
    }

    @Test
    void navigationAndOpeningReceiptsRequireAuthorizationAndRunOnEventThread() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        var supplier = fixture.suppliers.add("Northwind", "TIN");
        var receipt = scoped(Fakes.receipt("R-1", supplier, null,
                LocalDate.of(2026, 1, 2), "Paper", "10"), fixture.user.companyId());
        fixture.receipts.data.put(receipt.id(), receipt);
        AtomicReference<Module> navigated = new AtomicReference<>();
        AtomicBoolean callbacksOnEdt = new AtomicBoolean(true);
        DuckyToolRegistry registry = new DuckyToolRegistry(fixture.context,
                module -> {
                    callbacksOnEdt.set(callbacksOnEdt.get() && javax.swing.SwingUtilities.isEventDispatchThread());
                    navigated.set(module);
                }, id -> {
                    callbacksOnEdt.set(callbacksOnEdt.get() && javax.swing.SwingUtilities.isEventDispatchThread());
                    assertEquals(receipt.id(), id);
                });

        registry.execute("navigateToModule", JSON.readTree("{\"module\":\"receipts\"}"), DuckyPermission.ASSIST);
        registry.execute("openReceipt", JSON.readTree("{\"receiptId\":\"" + receipt.id() + "\"}"),
                DuckyPermission.ASSIST);

        assertEquals(Module.RECEIPTS, navigated.get());
        assertTrue(callbacksOnEdt.get());
        assertThrows(IllegalArgumentException.class, () -> registry.execute("navigateToModule",
                JSON.readTree("{\"module\":\"companies\"}"), DuckyPermission.ASSIST));
        assertThrows(IllegalArgumentException.class, () -> registry.execute("openReceipt",
                JSON.readTree("{\"receiptId\":\"" + UUID.randomUUID() + "\"}"), DuckyPermission.ASSIST));
    }

    @Test
    void supplierSearchMinimizesFieldsAndReceiptTotalsUseTheRequestedDateRange() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        var supplier = fixture.suppliers.add("Northwind Office", "TIN-PRIVATE-222");
        fixture.suppliers.data.put(supplier.id(), new com.bred.rms.models.Supplier(
                supplier.id(), supplier.businessName(), supplier.tin(), "Private address", "Private phone"));
        Receipt first = scoped(Fakes.receipt("R-1", supplier, null,
                LocalDate.of(2026, 1, 2), "Paper", "10.00"), fixture.user.companyId());
        Receipt second = scoped(Fakes.receipt("R-2", supplier, null,
                LocalDate.of(2026, 2, 2), "Ink", "20.00"), fixture.user.companyId());
        fixture.receipts.data.put(first.id(), first);
        fixture.receipts.data.put(second.id(), second);
        DuckyToolRegistry registry = fixture.registry();

        var suppliers = registry.execute("searchSuppliers", JSON.readTree("{\"query\":\"northwind\"}"),
                DuckyPermission.ASSIST);
        var totals = registry.execute("calculateReceiptTotals",
                JSON.readTree("{\"dateFrom\":\"2026-01-01\",\"dateTo\":\"2026-01-31\"}"),
                DuckyPermission.ASSIST);

        assertTrue(suppliers.requiresDisclosure());
        assertTrue(suppliers.content().contains("Northwind Office"));
        assertFalse(suppliers.content().contains("TIN-PRIVATE-222"));
        assertFalse(suppliers.content().contains("Private address"));
        assertTrue(totals.requiresDisclosure());
        assertTrue(totals.content().contains("10.00"));
        assertFalse(totals.content().contains("20.00"));
    }

    @Test
    void askModeAllowsOnlyConfirmedActionsAndReceiptCreationUsesValidatedCompanyData() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        var supplier = fixture.suppliers.add("Northwind Office", "TIN");
        Receipt seed = scoped(Fakes.receipt("SEED-1", supplier, null,
                LocalDate.of(2026, 1, 1), "Existing item", "1.00"), fixture.user.companyId());
        fixture.receipts.data.put(seed.id(), seed);
        AtomicReference<UUID> openedReceipt = new AtomicReference<>();
        AtomicBoolean formOpenedOnEdt = new AtomicBoolean();
        DuckyToolRegistry registry = new DuckyToolRegistry(fixture.context, ignored -> { },
                openedReceipt::set, () -> formOpenedOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread()));

        assertEquals(3, registry.definitions(DuckyPermission.ASK).size());
        assertTrue(registry.definitions(DuckyPermission.ASK).stream()
                .noneMatch(tool -> tool.name().equals("searchReceipts")));
        registry.execute("openReceiptEntry", JSON.readTree("{}"), DuckyPermission.ASK);
        assertTrue(formOpenedOnEdt.get());

        String request = """
                {
                  "receiptNo":"R-2026-7","date":"2026-03-11","type":"OFFICIAL_RECEIPT",
                  "supplierName":"Northwind Office","items":[
                    {"description":"Paper","quantity":2,"unitPrice":6.25}
                  ]
                }
                """;
        AtomicBoolean approved = new AtomicBoolean();
        AtomicReference<String> preview = new AtomicReference<>();
        var declined = registry.execute("createReceiptFromChat", JSON.readTree(request), DuckyPermission.ASK,
                (title, details) -> {
                    preview.set(title + "\n" + details);
                    return false;
                });
        assertFalse(declined.content().contains("saved"));
        assertEquals(1, fixture.receipts.data.size());

        var created = registry.execute("createReceiptFromChat", JSON.readTree(request), DuckyPermission.ASK,
                (title, details) -> {
                    assertEquals("Create this receipt in BRED?", title);
                    assertTrue(details.contains("Total: 12.50"));
                    approved.set(true);
                    return true;
                });
        assertTrue(approved.get());
        assertTrue(preview.get().contains("Northwind Office"));
        assertTrue(created.content().contains("R-2026-7"));
        assertEquals(2, fixture.receipts.data.size());
        Receipt saved = fixture.receipts.data.values().stream()
                .filter(receipt -> receipt.receiptNo().equals("R-2026-7")).findFirst().orElseThrow();
        assertEquals(fixture.user.companyId(), saved.companyId());
        assertEquals(supplier.id(), saved.supplierId());
        assertEquals(saved.id(), openedReceipt.get());
    }

    @Test
    void normalUsersNeverReceiveReceiptsFromAnotherCompany() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        var supplier = fixture.suppliers.add("Northwind", "TIN");
        Receipt own = scoped(Fakes.receipt("OWN-1", supplier, null,
                LocalDate.of(2026, 1, 2), "Paper", "10"), fixture.user.companyId());
        Receipt foreign = scoped(Fakes.receipt("OTHER-1", supplier, null,
                LocalDate.of(2026, 1, 3), "Ink", "20"), UUID.randomUUID());
        fixture.receipts.data.put(own.id(), own);
        fixture.receipts.data.put(foreign.id(), foreign);

        var result = fixture.registry().execute("getRecentReceipts", JSON.readTree("{}"), DuckyPermission.ASSIST);

        assertTrue(result.content().contains("OWN-1"));
        assertFalse(result.content().contains("OTHER-1"));
    }

    @Test
    void normalUsersCannotSearchSuppliersUsedOnlyByAnotherCompany() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        var ownSupplier = fixture.suppliers.add("Own Company Supplier", "TIN-OWN");
        var foreignSupplier = fixture.suppliers.add("Foreign Company Supplier", "TIN-OTHER");
        Receipt own = scoped(Fakes.receipt("OWN-1", ownSupplier, null,
                LocalDate.of(2026, 1, 2), "Paper", "10"), fixture.user.companyId());
        Receipt foreign = scoped(Fakes.receipt("OTHER-1", foreignSupplier, null,
                LocalDate.of(2026, 1, 3), "Ink", "20"), UUID.randomUUID());
        fixture.receipts.data.put(own.id(), own);
        fixture.receipts.data.put(foreign.id(), foreign);

        var result = fixture.registry().execute("searchSuppliers",
                JSON.readTree("{\"query\":\"Foreign Company\"}"), DuckyPermission.ASSIST);

        assertFalse(result.content().contains("Foreign Company Supplier"));
        assertTrue(result.content().contains("No authorized suppliers matched."));
    }

    @Test
    void bugReportsAreValidatedAndSubmittedOnlyAfterExplicitConfirmation() throws Exception {
        Fixture fixture = new Fixture(approvedUser());
        AtomicReference<String> submittedTitle = new AtomicReference<>();
        AtomicReference<String> submittedDescription = new AtomicReference<>();
        BugReportRepository repository = new BugReportRepository() {
            @Override public void submit(UUID reporterId, String title, String description,
                                         String fileName, byte[] image, String contentType) {
                assertEquals(fixture.user.id(), reporterId);
                submittedTitle.set(title);
                submittedDescription.set(description);
            }

            @Override public java.util.List<com.bred.rms.models.BugReport> findAll() { return java.util.List.of(); }
            @Override public byte[] downloadAttachment(String path) { return new byte[0]; }
        };
        DuckyToolRegistry registry = new DuckyToolRegistry(fixture.context, ignored -> { }, ignored -> { },
                null, new BugReportService(repository, () -> fixture.user));
        var request = JSON.readTree("{\"title\":\"Save button clips\",\"description\":\"The footer is clipped.\"}");

        registry.execute("submitBugReport", request, DuckyPermission.ASK, (title, details) -> false);
        assertTrue(submittedTitle.get() == null);
        var result = registry.execute("submitBugReport", request, DuckyPermission.ASK, (title, details) -> {
            assertEquals("Send this bug report to the Creator?", title);
            assertTrue(details.contains("Clark Hilario"));
            assertTrue(details.contains("The footer is clipped."));
            return true;
        });

        assertEquals("Save button clips", submittedTitle.get());
        assertEquals("The footer is clipped.", submittedDescription.get());
        assertTrue(result.content().contains("Clark Hilario"));
        assertThrows(IllegalArgumentException.class, () -> registry.execute("submitBugReport",
                JSON.readTree("{\"title\":\"Bug\",\"description\":\"API key: gsk_12345678901234567890\"}"),
                DuckyPermission.ASK, (title, details) -> true));
    }

    private static User approvedUser() {
        return new User(UUID.randomUUID(), "Test Admin", "admin@example.test", Role.ADMIN, true,
                AccountStatus.APPROVED, Instant.EPOCH, "Example Company", UUID.randomUUID());
    }

    private static Receipt scoped(Receipt receipt, UUID companyId) {
        return new Receipt(receipt.id(), receipt.receiptNo(), receipt.date(), receipt.type(), receipt.supplierId(),
                receipt.projectId(), receipt.items(), receipt.discount(), receipt.vat(), receipt.additionalCharges(),
                receipt.attachments(), receipt.encodedBy(), receipt.createdAt(), receipt.modifiedAt(), companyId);
    }

    private static final class Fixture {
        private final User user;
        private final Fakes.Receipts receipts = new Fakes.Receipts();
        private final Fakes.Suppliers suppliers = new Fakes.Suppliers();
        private final Fakes.Projects projects = new Fakes.Projects();
        private final Fakes.Attachments attachments = new Fakes.Attachments();
        private final AppContext context;

        private Fixture(User user) {
            this.user = user;
            CurrentUser current = () -> this.user;
            var receiptService = new ReceiptService(receipts, suppliers, projects, attachments, current);
            var supplierService = new SupplierService(suppliers, receipts, current);
            var projectService = new ProjectService(projects, receipts, current);
            context = AppContext.forTesting(user, receiptService, supplierService, projectService, attachments);
        }

        private DuckyToolRegistry registry() {
            return new DuckyToolRegistry(context, ignored -> { }, ignored -> { });
        }
    }
}
