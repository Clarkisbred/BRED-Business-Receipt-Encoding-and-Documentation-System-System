package com.bred.rms.models;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplierTest {
    @Test
    void preservesLegacySuppliersAsUnclassifiedAndNormalizesExplicitClassification() {
        Supplier legacy = new Supplier(UUID.randomUUID(), "North Supply", "123", "", "");
        Supplier vatRegistered = new Supplier(UUID.randomUUID(), "Taxable Supply", "456", "", "", "vat_registered");

        assertEquals("UNCLASSIFIED", legacy.taxClassification());
        assertEquals("VAT_REGISTERED", vatRegistered.taxClassification());
    }

    @Test
    void rejectsUnsupportedTaxClassifications() {
        assertThrows(IllegalArgumentException.class,
                () -> new Supplier(UUID.randomUUID(), "Supplier", "789", "", "", "UNKNOWN"));
    }
}
