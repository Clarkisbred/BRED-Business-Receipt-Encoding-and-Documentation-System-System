package com.bred.rms.importer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReceiptAmountParserTest {
    @Test
    void parsesSupportedCurrencyAndGroupingFormats() {
        assertEquals(new BigDecimal("1234.50"), ReceiptAmountParser.parse("USD 1,234.50").orElseThrow());
        assertEquals(new BigDecimal("-12.00"), ReceiptAmountParser.parse("(₱12.00)").orElseThrow());
        assertEquals(new BigDecimal("0.50"), ReceiptAmountParser.parse(".50").orElseThrow());
    }

    @Test
    void rejectsAmbiguousDecimalCommaAndMalformedGrouping() {
        assertFalse(ReceiptAmountParser.parse("1.234,56").isPresent());
        assertFalse(ReceiptAmountParser.parse("1234,56").isPresent());
        assertFalse(ReceiptAmountParser.parse("12,34").isPresent());
    }
}
