package com.bred.rms.models;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptMathTest {
    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static Receipt receipt(List<ReceiptItem> items, String discount, String vat, String charges) {
        return new Receipt(UUID.randomUUID(), "OR-001", LocalDate.of(2026, 11, 1), ReceiptType.OFFICIAL_RECEIPT,
                UUID.randomUUID(), null, items, d(discount), d(vat), d(charges), List.of(),
                UUID.randomUUID(), Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void itemsOfPoint1AndPoint2TotalExactlyPoint30() {
        Receipt r = receipt(List.of(new ReceiptItem("a", d("1"), d("0.10")), new ReceiptItem("b", d("1"), d("0.20"))), "0", "0", "0");
        assertEquals(new BigDecimal("0.30"), r.subtotal());
    }

    @Test
    void grandTotalIsSubtotalMinusDiscountPlusVatPlusCharges() {
        Receipt r = receipt(List.of(new ReceiptItem("a", d("10"), d("100.00"))), "100.00", "108.00", "50.00");
        assertEquals(new BigDecimal("1000.00"), r.subtotal());
        assertEquals(new BigDecimal("1058.00"), r.grandTotal());
    }

    @Test
    void ewtIsCalculatedFromVatExclusiveReceiptTotalAndDeductedFromNetPayable() {
        Receipt r = new Receipt(UUID.randomUUID(), "OR-002", LocalDate.of(2026, 11, 1),
                ReceiptType.OFFICIAL_RECEIPT, UUID.randomUUID(), null,
                List.of(new ReceiptItem("a", d("3"), "kg", d("100.00"))),
                d("0"), d("12.00"), d("0"), List.of(), null, null, null, null,
                "For project supplies", "Card", d("2.5"));

        assertEquals("kg", r.items().get(0).unit());
        assertEquals(new BigDecimal("312.00"), r.grandTotal());
        assertEquals(new BigDecimal("6.96"), r.ewtAmount());
        assertEquals(new BigDecimal("305.04"), r.netTotal());
        assertEquals("For project supplies", r.notes());
        assertEquals("Card", r.paymentMethod());
    }

    @Test
    void defaultVatIsTwelvePercentOfNet() {
        assertEquals(new BigDecimal("108.00"), ReceiptMath.defaultVat(d("1000.00"), d("100.00")));
    }

    @Test
    void itemSubtotalRoundsHalfUpToTwoPlaces() {
        assertEquals(new BigDecimal("3.35"), new ReceiptItem("x", d("3"), d("1.115")).subtotal());
    }

    @Test
    void formatShowsPesoAndGrouping() {
        assertEquals("₱1,234.50", ReceiptMath.format(d("1234.5")));
    }
}
