package com.bred.rms.importer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportedLineItemsTableModelTest {
    @Test
    void supportsAddingEditingAndRemovingReviewOnlyLineItems() {
        ReceiptCandidate candidate = new ReceiptCandidate(UUID.randomUUID(), UUID.randomUUID(), "x.csv",
                "row 2", null, null, null,
                List.of(new ImportedLineItem("Paper", "1", "ream", "10.00", "10.00")));
        AtomicInteger updates = new AtomicInteger();
        ImportedLineItemsTableModel model = new ImportedLineItemsTableModel(candidate, updates::incrementAndGet);

        model.addItem();
        model.setValueAt("Pen", 1, 0);
        model.setValueAt("2", 1, 1);
        assertEquals("Pen", model.getValueAt(1, 0));
        assertEquals("2", model.getValueAt(1, 1));
        model.removeItem(0);

        assertEquals(1, model.getRowCount());
        assertEquals("Pen", model.items().get(0).description());
        assertEquals(4, updates.get());
    }
}
