package com.bred.rms.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoyaltyCalculationsTest {
    @Test
    void matchesTheSpecifiedNonVatExampleAndAddsOnlyActualReturnsToNet() {
        RoyaltyCalculations.Result pending = RoyaltyCalculations.calculate(input(
                "254549.21", "0", RoyaltyCalculations.VatTreatment.NON_VAT,
                "3", "2", null, null, null, "0"));

        assertMoney("7636.48", pending.royaltyAmount());
        assertMoney("5090.98", pending.withholdingAmount());
        assertMoney("241821.75", pending.initialNetAmount());
        assertMoney("5090.98", pending.outstandingReturn());
        assertMoney("241821.75", pending.finalNetAmount());

        RoyaltyCalculations.Result completed = RoyaltyCalculations.calculate(input(
                "254549.21", "0", RoyaltyCalculations.VatTreatment.NON_VAT,
                "3", "2", null, null, null, "5090.98"));
        assertMoney("0", completed.outstandingReturn());
        assertMoney("246912.73", completed.finalNetAmount());
    }

    @Test
    void defaultsVatInclusiveBasesToTheVatExclusiveAmount() {
        RoyaltyCalculations.Result result = RoyaltyCalculations.calculate(input(
                "1120", "0", RoyaltyCalculations.VatTreatment.VAT_INCLUSIVE,
                "3", "2", null, null, null, "0"));

        assertMoney("1000", result.vatExclusiveAmount());
        assertMoney("120", result.inputVatAmount());
        assertMoney("30", result.royaltyAmount());
        assertMoney("20", result.withholdingAmount());
        assertMoney("1070", result.initialNetAmount());
    }

    @Test
    void keepsEachDeductionBasisIndependentAndBlankOptionalRatesInactive() {
        RoyaltyCalculations.Input input = new RoyaltyCalculations.Input(
                new BigDecimal("1120"), BigDecimal.ZERO, RoyaltyCalculations.VatTreatment.VAT_INCLUSIVE,
                new BigDecimal("12"), new BigDecimal("3"), RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                new BigDecimal("2"), RoyaltyCalculations.Basis.BILLING_AMOUNT,
                null, RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                null, RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                null, RoyaltyCalculations.Basis.VAT_EXCLUSIVE, BigDecimal.ZERO);

        RoyaltyCalculations.Result result = RoyaltyCalculations.calculate(input);

        assertMoney("30", result.royaltyAmount());
        assertMoney("22.40", result.withholdingAmount());
        assertMoney("0", result.additionalWithholdingAmount());
        assertMoney("0", result.ewtAmount());
        assertMoney("0", result.retentionAmount());
    }

    @Test
    void rejectsNegativeAmountsInvalidRatesAndExcessReturns() {
        assertThrows(IllegalArgumentException.class, () -> RoyaltyCalculations.calculate(input(
                "-1", "0", RoyaltyCalculations.VatTreatment.NON_VAT,
                "3", "2", null, null, null, "0")));
        assertThrows(IllegalArgumentException.class, () -> RoyaltyCalculations.calculate(input(
                "100", "0", RoyaltyCalculations.VatTreatment.NON_VAT,
                "101", "2", null, null, null, "0")));
        assertThrows(IllegalArgumentException.class, () -> RoyaltyCalculations.calculate(input(
                "100", "0", RoyaltyCalculations.VatTreatment.NON_VAT,
                "3", "2", null, null, null, "2.01")));
    }

    private static RoyaltyCalculations.Input input(String billing, String vat, RoyaltyCalculations.VatTreatment treatment,
                                                    String royaltyRate, String withholdingRate,
                                                    String additionalRate, String ewtRate, String retentionRate,
                                                    String returned) {
        return new RoyaltyCalculations.Input(
                new BigDecimal(billing), new BigDecimal(vat), treatment, new BigDecimal("12"),
                new BigDecimal(royaltyRate), RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                new BigDecimal(withholdingRate), RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                decimal(additionalRate), RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                decimal(ewtRate), RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                decimal(retentionRate), RoyaltyCalculations.Basis.VAT_EXCLUSIVE,
                new BigDecimal(returned));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
