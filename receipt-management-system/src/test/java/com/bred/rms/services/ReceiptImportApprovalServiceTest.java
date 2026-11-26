package com.bred.rms.services;

import com.bred.rms.importer.ImportedLineItem;
import com.bred.rms.importer.ReceiptCandidate;
import com.bred.rms.importer.ReceiptField;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Company;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.repositories.UserRepository;
import com.bred.rms.support.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptImportApprovalServiceTest {
    @TempDir Path tempDir;

    @Test
    void requiresActiveAdministratorAndCompanyContext() {
        Fixture encoder = new Fixture(Role.ENCODER, UUID.randomUUID());
        assertThrows(SecurityException.class, () -> encoder.service.loadCompanySuppliers());
        var supplier = encoder.supplier();
        Receipt receipt = Fakes.receipt("R-NO-ADMIN", supplier, null,
                java.time.LocalDate.of(2026, 11, 5), "Paper", "10");
        var prepared = new ReceiptImportApprovalService.Prepared(UUID.randomUUID(), receipt, null, "",
                supplier.businessName(), List.of(), List.of());
        assertEquals(ReceiptImportApprovalService.ResultType.FAILED,
                encoder.service.approve(List.of(prepared), null).get(0).type());
        assertEquals(0, encoder.receipts.approvedWrites);

        Fixture noCompany = new Fixture(Role.ADMIN, null);
        assertThrows(SecurityException.class, () -> noCompany.service.loadCompanySuppliers());
    }

    @Test
    void previewReportsDuplicatesAndSupplierIdentityConflicts() {
        Fixture fixture = new Fixture(Role.ADMIN, UUID.randomUUID());
        var supplier = fixture.supplier();
        fixture.receipts.data.put(UUID.randomUUID(),
                Fakes.receipt("R-1", supplier, null, java.time.LocalDate.of(2026, 11, 5), "Paper", "10"));
        var candidate = candidate("R-1", "Other Shop", "999");

        var prepared = fixture.service.preview(List.of(new ReceiptImportApprovalService.Draft(
                candidate.id(), candidate, supplier.id(), null)));

        assertFalse(prepared.get(0).possibleDuplicates().isEmpty());
        assertTrue(prepared.get(0).warnings().stream().anyMatch(w -> w.contains("supplier name differs")));
        assertTrue(prepared.get(0).warnings().stream().anyMatch(w -> w.contains("TIN differs")));
    }

    @Test
    void savesMultipleReceiptsAndDoesNotSubmitSuccessfulDraftsTwice() {
        Fixture fixture = new Fixture(Role.ADMIN, UUID.randomUUID());
        var supplier = fixture.supplier();
        var prepared = fixture.service.preview(List.of(
                draft(candidate("R-1", supplier.businessName(), supplier.tin()), supplier.id()),
                draft(candidate("R-2", supplier.businessName(), supplier.tin()), supplier.id())));

        var first = fixture.service.approve(prepared, null);
        var second = fixture.service.approve(prepared, null);

        assertEquals(2, fixture.receipts.approvedWrites);
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(second.stream().allMatch(result -> result.message().contains("Already saved")));
    }

    @Test
    void reportsPartialFailureWithoutLosingSuccessfulReceipt() {
        Fixture fixture = new Fixture(Role.ADMIN, UUID.randomUUID());
        var supplier = fixture.supplier();
        fixture.receipts.failApprovedNo = "R-FAIL";
        var prepared = fixture.service.preview(List.of(
                draft(candidate("R-OK", supplier.businessName(), supplier.tin()), supplier.id()),
                draft(candidate("R-FAIL", supplier.businessName(), supplier.tin()), supplier.id())));

        var results = fixture.service.approve(prepared, null);

        assertEquals(2, results.size());
        assertEquals(ReceiptImportApprovalService.ResultType.SAVED_WITHOUT_ATTACHMENT, results.get(0).type());
        assertEquals(ReceiptImportApprovalService.ResultType.FAILED, results.get(1).type());
        assertEquals(1, fixture.receipts.data.size());
        assertNotNull(results.get(1).message());
    }

    @Test
    void attachmentFailureKeepsReceiptAndRetryDoesNotCreateAnotherReceipt() throws Exception {
        Fixture fixture = new Fixture(Role.ADMIN, UUID.randomUUID());
        var supplier = fixture.supplier();
        Path pdf = tempDir.resolve("source.pdf");
        Files.writeString(pdf, "test receipt");
        fixture.attachments.failUpload = true;
        ReceiptCandidate candidate = candidate("R-ATTACH", supplier.businessName(), supplier.tin());
        var prepared = fixture.service.preview(List.of(
                draft(candidate, supplier.id(), pdf)));

        var failed = fixture.service.approve(prepared, null).get(0);
        fixture.attachments.failUpload = false;
        var retryPreview = fixture.service.preview(List.of(draft(candidate, supplier.id(), pdf)));
        var retried = fixture.service.approve(retryPreview, null).get(0);

        assertEquals(ReceiptImportApprovalService.ResultType.ATTACHMENT_FAILED, failed.type());
        assertNotNull(failed.receiptId());
        assertTrue(retryPreview.get(0).possibleDuplicates().isEmpty());
        assertEquals(ReceiptImportApprovalService.ResultType.SAVED, retried.type());
        assertEquals(1, fixture.receipts.approvedWrites);
        assertEquals(2, fixture.attachments.uploadCalls);
        assertEquals(1, fixture.receipts.approvedAttachmentWrites);
    }

    @Test
    void revalidatesRoleImmediatelyBeforeSaving() {
        Fixture fixture = new Fixture(Role.ADMIN, UUID.randomUUID());
        var supplier = fixture.supplier();
        var prepared = fixture.service.preview(List.of(
                draft(candidate("R-REVOKED", supplier.businessName(), supplier.tin()), supplier.id())));
        fixture.profiles.profile = user(fixture.profiles.profile.id(), Role.ENCODER, fixture.profiles.profile.companyId());

        var result = fixture.service.approve(prepared, null).get(0);

        assertEquals(ReceiptImportApprovalService.ResultType.FAILED, result.type());
        assertEquals(0, fixture.receipts.approvedWrites);
    }

    private static ReceiptImportApprovalService.Draft draft(ReceiptCandidate candidate, UUID supplierId) {
        return draft(candidate, supplierId, null);
    }

    private static ReceiptImportApprovalService.Draft draft(ReceiptCandidate candidate, UUID supplierId, Path source) {
        return new ReceiptImportApprovalService.Draft(candidate.id(), candidate, supplierId, source);
    }

    private static ReceiptCandidate candidate(String number, String supplier, String tin) {
        return new ReceiptCandidate(null, UUID.randomUUID(), "receipt.csv", "row 2",
                Map.of(ReceiptField.RECEIPT_NUMBER, number, ReceiptField.DATE, "2026-11-05",
                        ReceiptField.SUPPLIER_NAME, supplier, ReceiptField.TIN, tin,
                        ReceiptField.DISCOUNT, "0", ReceiptField.TAX, "0",
                        ReceiptField.SUBTOTAL, "10", ReceiptField.GRAND_TOTAL, "10"),
                Map.of(), List.of(),
                List.of(new ImportedLineItem("Paper", "1", "box", "10", "10")));
    }

    private static User user(UUID id, Role role, UUID companyId) {
        return new User(id, "Test Admin", "test@example.test", role, true, AccountStatus.APPROVED,
                Instant.EPOCH, "Test Company", companyId);
    }

    private static final class Fixture {
        final UUID companyId;
        final User signedIn;
        final Fakes.Receipts receipts = new Fakes.Receipts();
        final Fakes.Suppliers suppliers = new Fakes.Suppliers();
        final Fakes.Projects projects = new Fakes.Projects();
        final Fakes.Attachments attachments = new Fakes.Attachments();
        final Profiles profiles;
        final ReceiptImportApprovalService service;

        Fixture(Role role, UUID companyId) {
            this.companyId = companyId;
            signedIn = user(UUID.randomUUID(), role, companyId);
            profiles = new Profiles(signedIn);
            CurrentUser currentUser = () -> signedIn;
            var receiptService = new ReceiptService(receipts, suppliers, projects, attachments, currentUser);
            service = new ReceiptImportApprovalService(receiptService,
                    new SupplierService(suppliers, receipts, currentUser), profiles, attachments, currentUser);
        }

        com.bred.rms.models.Supplier supplier() {
            return suppliers.add("Acme Hardware", "123-456-789");
        }
    }

    private static final class Profiles implements UserRepository {
        User profile;

        Profiles(User profile) { this.profile = profile; }

        @Override public List<User> findAll() { return List.of(profile); }
        @Override public Optional<User> findById(UUID id) {
            return profile.id().equals(id) ? Optional.of(profile) : Optional.empty();
        }
        @Override public User create(String email, String fullName, Role role, char[] password) {
            throw new UnsupportedOperationException();
        }
        @Override public void update(UUID id, String fullName, Role role, boolean active) {
            throw new UnsupportedOperationException();
        }
        @Override public void delete(UUID id) { throw new UnsupportedOperationException(); }
        @Override public void sendPasswordReset(UUID id) { throw new UnsupportedOperationException(); }
        @Override public void registerAccount(String fullName, String email, Role role, UUID companyId,
                                              String companyName, char[] password) {
            throw new UnsupportedOperationException();
        }
        @Override public List<Company> availableCompanies() { throw new UnsupportedOperationException(); }
        @Override public void reviewRegistration(UUID id, AccountStatus decision) {
            throw new UnsupportedOperationException();
        }
    }
}
