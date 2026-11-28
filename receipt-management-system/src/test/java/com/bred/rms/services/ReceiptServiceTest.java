package com.bred.rms.services;

import com.bred.rms.models.Attachment;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.models.ValidationException;
import com.bred.rms.support.Fakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptServiceTest {
    Fakes.Receipts receipts;
    Fakes.Suppliers suppliers;
    Fakes.Projects projects;
    Fakes.Attachments attachments;
    User current;
    ReceiptService service;
    Supplier acme;
    Supplier beta;
    Project bridge;

    @BeforeEach
    void setUp() {
        receipts = new Fakes.Receipts();
        suppliers = new Fakes.Suppliers();
        projects = new Fakes.Projects();
        attachments = new Fakes.Attachments();
        current = Fakes.encoder();
        service = new ReceiptService(receipts, suppliers, projects, attachments, () -> current);
        acme = suppliers.add("Acme Hardware", "111-222");
        beta = suppliers.add("Beta Supplies", "999-888");
        bridge = projects.add("Bridge Repair", "BR-01");
    }

    private Receipt base(String no, Supplier s) {
        return Fakes.receipt(no, s, null, LocalDate.of(2026, 11, 1), "Nails", "100.00");
    }

    private Receipt withItems(Receipt r, ReceiptItem... items) {
        return new Receipt(r.id(), r.receiptNo(), r.date(), r.type(), r.supplierId(), r.projectId(), List.of(items),
                r.discount(), r.vat(), r.additionalCharges(), r.attachments(), r.encodedBy(), r.createdAt(), r.modifiedAt());
    }

    private Receipt withMoney(Receipt r, String discount) {
        return new Receipt(r.id(), r.receiptNo(), r.date(), r.type(), r.supplierId(), r.projectId(), r.items(),
                new BigDecimal(discount), r.vat(), r.additionalCharges(), r.attachments(), r.encodedBy(), r.createdAt(), r.modifiedAt());
    }

    @Test
    void saveRejectsEmptyItems() {
        ValidationException e = assertThrows(ValidationException.class, () -> service.save(withItems(base("OR-1", acme))));
        assertTrue(e.getMessage().contains("at least one item"));
    }

    @Test
    void saveRejectsNegativeQuantityPriceOrDiscount() {
        assertThrows(ValidationException.class, () -> service.save(withItems(base("OR-1", acme), new ReceiptItem("x", new BigDecimal("-1"), BigDecimal.TEN))));
        assertThrows(ValidationException.class, () -> service.save(withItems(base("OR-1", acme), new ReceiptItem("x", BigDecimal.ZERO, BigDecimal.TEN))));
        assertThrows(ValidationException.class, () -> service.save(withItems(base("OR-1", acme), new ReceiptItem("x", BigDecimal.ONE, new BigDecimal("-0.01")))));
        assertThrows(ValidationException.class, () -> service.save(withMoney(base("OR-1", acme), "-5")));
        assertThrows(ValidationException.class, () -> service.save(withItems(base("OR-1", acme), new ReceiptItem("  ", BigDecimal.ONE, BigDecimal.TEN))));
    }

    @Test
    void saveRejectsDiscountAboveSubtotal() {
        ValidationException e = assertThrows(ValidationException.class, () -> service.save(withMoney(base("OR-1", acme), "100.01")));
        assertTrue(e.getMessage().contains("Discount"));
        service.save(withMoney(base("OR-2", acme), "100.00"));
    }

    @Test
    void saveRejectsDuplicateReceiptNoForSameSupplierTin() {
        service.save(base("OR-100", acme));
        ValidationException e = assertThrows(ValidationException.class, () -> service.save(base("  or-100 ", acme)));
        assertTrue(e.getMessage().contains("Duplicate receipt"));
        Supplier acmeTwin = suppliers.add("Acme Twin Branch", " 111-222 ");
        assertThrows(ValidationException.class, () -> service.save(base("OR-100", acmeTwin)));
    }

    @Test
    void allowsSameReceiptNoForDifferentTin() {
        service.save(base("OR-100", acme));
        service.save(base("OR-100", beta));
        assertEquals(2, receipts.data.size());
    }

    @Test
    void categoryMatchingIsCaseInsensitiveAndStoredUppercase() {
        service.save(base("OR-100", acme).withClassification("Office supplies"));

        Receipt saved = service.save(base("OR-101", beta).withClassification("office SUPPLIES"));

        assertEquals("OFFICE SUPPLIES", saved.classification());
    }

    @Test
    void editingAReceiptDoesNotClashWithItself() {
        Receipt saved = service.save(base("OR-100", acme));
        service.save(withMoney(saved, "10.00"));
        assertEquals(1, receipts.data.size());
    }

    @Test
    void saveRemovesDetachedAttachmentsAndSurvivesRemovalFailure() {
        Attachment a1 = new Attachment("a.pdf", "r/a.pdf");
        Attachment a2 = new Attachment("b.pdf", "r/b.pdf");
        Receipt r = base("OR-7", acme);
        Receipt saved = service.save(new Receipt(null, r.receiptNo(), r.date(), r.type(), r.supplierId(), null, r.items(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(a1, a2), null, null, null));
        Receipt edited = new Receipt(saved.id(), saved.receiptNo(), saved.date(), saved.type(), saved.supplierId(), null, saved.items(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(a1), null, null, null);
        attachments.failRemoval = true;
        service.save(edited);
        attachments.failRemoval = false;
        service.save(new Receipt(saved.id(), saved.receiptNo(), saved.date(), saved.type(), saved.supplierId(), null, saved.items(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null, null, null));
        assertEquals(List.of(a1), attachments.removed);
    }

    @Test
    void encoderCannotDelete() {
        Receipt saved = service.save(base("OR-1", acme));
        assertThrows(SecurityException.class, () -> service.delete(saved.id()));
        assertEquals(1, receipts.data.size());
    }

    @Test
    void adminCanDeleteAndStoredFilesAreRemoved() {
        Receipt r = base("OR-1", acme);
        Receipt saved = service.save(new Receipt(null, r.receiptNo(), r.date(), r.type(), r.supplierId(), null, r.items(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(new Attachment("a.pdf", "r/a.pdf")), null, null, null));
        current = Fakes.admin();
        service.delete(saved.id());
        assertEquals(0, receipts.data.size());
        assertEquals("r/a.pdf", attachments.removed.get(0).storagePath());
    }

    @Test
    void searchMatchesTinSupplierItemProjectAndDateRange() {
        Receipt r1 = receipts.save(Fakes.receipt("OR-1", acme, bridge, LocalDate.of(2026, 10, 5), "Cement bags", "50"));
        Receipt r2 = receipts.save(Fakes.receipt("SI-22", beta, null, LocalDate.of(2026, 11, 20), "Paint", "75"));
        assertEquals(List.of(r1), service.search(new ReceiptQuery("111-222", null, null, null, null)));
        assertEquals(List.of(r2), service.search(new ReceiptQuery("beta sup", null, null, null, null)));
        assertEquals(List.of(r1), service.search(new ReceiptQuery("CEMENT", null, null, null, null)));
        assertEquals(List.of(r1), service.search(new ReceiptQuery("bridge", null, null, null, null)));
        assertEquals(List.of(r2), service.search(new ReceiptQuery("si-22", null, null, null, null)));
        assertEquals(List.of(r2), service.search(new ReceiptQuery("", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, null)));
        assertEquals(List.of(r1), service.search(new ReceiptQuery(null, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), null, null)));
        assertEquals(List.of(r1), service.search(new ReceiptQuery(null, null, null, acme.id(), bridge.id())));
        assertEquals(List.of(r2, r1), service.search(ReceiptQuery.all()));
    }

    @Test
    void precheckAppliesAllSaveRulesWithoutSavingAnything() {
        assertThrows(ValidationException.class, () -> service.precheck(withItems(base("A-1", acme), new ReceiptItem("Nails", BigDecimal.ZERO, BigDecimal.TEN))));
        assertThrows(ValidationException.class, () -> service.precheck(withMoney(base("A-2", acme), "999.00")));
        service.save(base("A-3", acme));
        ValidationException dup = assertThrows(ValidationException.class, () -> service.precheck(base(" a-3 ", acme)));
        assertTrue(dup.getMessage().contains("Duplicate receipt"), dup.getMessage());
        service.precheck(base("A-4", acme));
        assertEquals(1, receipts.findAll().size());
    }
}
