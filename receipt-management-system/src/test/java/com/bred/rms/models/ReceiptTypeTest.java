package com.bred.rms.models;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptTypeTest {
    @Test
    void salaryIsAddedWithoutRemovingExistingDocumentTypes() {
        assertEquals(List.of(
                "Official Receipt", "Sales Invoice", "Cash Invoice",
                "Delivery Receipt", "Salary", "Other"),
                Arrays.stream(ReceiptType.values()).map(ReceiptType::label).toList());
        assertEquals("salary", ReceiptType.SALARY.dbValue());
        assertEquals(ReceiptType.SALARY, ReceiptType.fromDb("salary"));
    }
}
