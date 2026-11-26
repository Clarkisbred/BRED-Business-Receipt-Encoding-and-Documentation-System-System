package com.bred.rms.ai;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.security.Module;
import com.bred.rms.services.ProjectService;
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
        var receipt = Fakes.receipt("RCPT-SECRET-7", supplier, null, LocalDate.of(2026, 1, 2), "Paper", "42.50");
        fixture.receipts.data.put(receipt.id(), receipt);
        DuckyToolRegistry registry = fixture.registry();

        var result = registry.execute("searchReceipts", JSON.readTree("{\"query\":\"RCPT-SECRET-7\"}"),
                DuckyPermission.ASSIST);

        assertTrue(result.requiresDisclosure());
        assertTrue(result.content().contains("RCPT-SECRET-7"));
        assertFalse(result.content().contains("TIN-PRIVATE-123"));
        assertFalse(result.content().contains("Private address"));
        assertEquals(11, registry.definitions().size());
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
        var receipt = Fakes.receipt("R-1", supplier, null, LocalDate.of(2026, 1, 2), "Paper", "10");
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
        fixture.receipts.data.put(UUID.randomUUID(),
                Fakes.receipt("R-1", supplier, null, LocalDate.of(2026, 1, 2), "Paper", "10.00"));
        fixture.receipts.data.put(UUID.randomUUID(),
                Fakes.receipt("R-2", supplier, null, LocalDate.of(2026, 2, 2), "Ink", "20.00"));
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

    private static User approvedUser() {
        return new User(UUID.randomUUID(), "Test Admin", "admin@example.test", Role.ADMIN, true,
                AccountStatus.APPROVED, Instant.EPOCH, "Example Company", UUID.randomUUID());
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
