package com.bred.rms.ui;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Supplier;
import com.bred.rms.support.Fakes;
import com.bred.rms.ui.receipts.ReceiptTableModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptTableModelTest {
    private final Supplier acme = new Supplier(UUID.randomUUID(), "Acme Hardware", "111", "", "");
    private final Project bridge = new Project(UUID.randomUUID(), "Bridge Repair", "BR-01", "");

    @Test
    void columnsAndValuesMatchReceipt() {
        Receipt r = Fakes.receipt("OR-100", acme, null, LocalDate.of(2026, 11, 5), "Nails", "1234.50");
        ReceiptTableModel m = new ReceiptTableModel(List.of(r), Map.of(acme.id(), acme), Map.of());
        assertEquals(List.of("Receipt No.", "Date", "Supplier", "Project", "Company", "Total", "Files"),
                List.of(m.getColumnName(0), m.getColumnName(1), m.getColumnName(2), m.getColumnName(3),
                        m.getColumnName(4), m.getColumnName(5), m.getColumnName(6)));
        assertEquals("OR-100", m.getValueAt(0, 0));
        assertEquals("Nov 5, 2026", m.getValueAt(0, 1));
        assertEquals("Acme Hardware", m.getValueAt(0, 2));
        assertEquals("—", m.getValueAt(0, 3));
        assertEquals("—", m.getValueAt(0, 4));
        assertEquals("₱1,234.50", m.getValueAt(0, 5));
        assertEquals("—", m.getValueAt(0, 6));
        assertEquals(r.id(), m.receiptAt(0).id());
    }

    @Test
    void showsProjectNameAndAttachmentCount() {
        Receipt base = Fakes.receipt("OR-1", acme, bridge, LocalDate.of(2026, 11, 5), "Nails", "10");
        Receipt r = new Receipt(base.id(), base.receiptNo(), base.date(), base.type(), base.supplierId(), base.projectId(), base.items(),
                base.discount(), base.vat(), base.additionalCharges(),
                List.of(new com.bred.rms.models.Attachment("a.pdf", "x/a.pdf"), new com.bred.rms.models.Attachment("b.png", "x/b.png")),
                null, base.createdAt(), base.modifiedAt());
        ReceiptTableModel m = new ReceiptTableModel(List.of(r), Map.of(acme.id(), acme), Map.of(bridge.id(), bridge));
        assertEquals("Bridge Repair", m.getValueAt(0, 3));
        assertEquals("—", m.getValueAt(0, 4));
        assertEquals("2", m.getValueAt(0, 6));
    }

    @Test
    void sortsByDateDescendingByDefault() {
        Receipt old = Fakes.receipt("A", acme, null, LocalDate.of(2026, 1, 1), "x", "1");
        Receipt recent = Fakes.receipt("B", acme, null, LocalDate.of(2026, 11, 1), "x", "1");
        ReceiptTableModel m = new ReceiptTableModel(List.of(old, recent), Map.of(acme.id(), acme), Map.of());
        assertEquals("B", m.getValueAt(0, 0));
        assertEquals("A", m.getValueAt(1, 0));
        assertEquals(2, m.getRowCount());
    }

    @Test
    void unknownSupplierShowsDash() {
        Receipt r = Fakes.receipt("A", acme, null, LocalDate.of(2026, 1, 1), "x", "1");
        assertEquals("—", new ReceiptTableModel(List.of(r), Map.of(), Map.of()).getValueAt(0, 2));
    }
}
