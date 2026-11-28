package com.bred.rms.services;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure, decimal-only calculations used by royalty records and their previews. */
public final class RoyaltyCalculations {
    public enum VatTreatment {
        VAT_INCLUSIVE,
        VAT_EXCLUSIVE,
        NON_VAT,
        VAT_EXEMPT,
        UNCLASSIFIED
    }

    public enum Basis {
        BILLING_AMOUNT,
        VAT_EXCLUSIVE
    }

    public record Input(
            BigDecimal billingAmount,
            BigDecimal vatAmount,
            VatTreatment vatTreatment,
            BigDecimal vatRate,
            BigDecimal royaltyRate,
            Basis royaltyBasis,
            BigDecimal withholdingRate,
            Basis withholdingBasis,
            BigDecimal additionalWithholdingRate,
            Basis additionalWithholdingBasis,
            BigDecimal ewtRate,
            Basis ewtBasis,
            BigDecimal retentionRate,
            Basis retentionBasis,
            BigDecimal amountReturned) { }

    public record Result(
            BigDecimal grossBillingAmount,
            BigDecimal inputVatAmount,
            BigDecimal vatExclusiveAmount,
            BigDecimal royaltyBase,
            BigDecimal royaltyAmount,
            BigDecimal withholdingBase,
            BigDecimal withholdingAmount,
            BigDecimal additionalWithholdingAmount,
            BigDecimal ewtAmount,
            BigDecimal retentionAmount,
            BigDecimal totalDeductions,
            BigDecimal initialNetAmount,
            BigDecimal amountReturned,
            BigDecimal outstandingReturn,
            BigDecimal finalNetAmount) { }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private RoyaltyCalculations() { }

    public static Result calculate(Input input) {
        if (input == null) throw new IllegalArgumentException("Royalty calculation details are required.");
        BigDecimal billing = nonNegative(input.billingAmount(), "Billing amount");
        BigDecimal vatRate = rate(input.vatRate(), "VAT rate");
        BigDecimal declaredVat = nonNegative(input.vatAmount(), "VAT amount");
        VatTreatment treatment = input.vatTreatment() == null ? VatTreatment.UNCLASSIFIED : input.vatTreatment();

        BigDecimal vatExclusive = billing;
        BigDecimal inputVat = ZERO;
        BigDecimal grossBilling = billing;
        if (treatment == VatTreatment.VAT_INCLUSIVE) {
            BigDecimal divisor = BigDecimal.ONE.add(vatRate.movePointLeft(2));
            vatExclusive = money(billing.divide(divisor, 8, RoundingMode.HALF_UP));
            inputVat = money(billing.subtract(vatExclusive));
        } else if (treatment == VatTreatment.VAT_EXCLUSIVE) {
            inputVat = money(declaredVat);
            grossBilling = money(billing.add(inputVat));
        } else if (declaredVat.signum() > 0) {
            throw new IllegalArgumentException("VAT can only be recorded for VAT-inclusive or VAT-exclusive billing.");
        }

        BigDecimal royaltyBase = base(input.royaltyBasis(), billing, vatExclusive);
        BigDecimal withholdingBase = base(input.withholdingBasis(), billing, vatExclusive);
        BigDecimal additionalWithholdingBase = base(input.additionalWithholdingBasis(), billing, vatExclusive);
        BigDecimal ewtBase = base(input.ewtBasis(), billing, vatExclusive);
        BigDecimal retentionBase = base(input.retentionBasis(), billing, vatExclusive);
        BigDecimal royalty = percentage(royaltyBase, rate(input.royaltyRate(), "Royalty rate"));
        BigDecimal withholding = percentage(withholdingBase,
                rate(input.withholdingRate(), "Withholding rate"));
        BigDecimal additionalWithholding = optionalPercentage(
                additionalWithholdingBase, input.additionalWithholdingRate(), "Additional withholding rate");
        BigDecimal ewt = optionalPercentage(ewtBase, input.ewtRate(), "EWT rate");
        BigDecimal retention = optionalPercentage(retentionBase, input.retentionRate(), "Retention rate");
        BigDecimal deductions = money(royalty.add(withholding).add(additionalWithholding).add(ewt).add(retention));
        if (deductions.compareTo(grossBilling) > 0) {
            throw new IllegalArgumentException("Total deductions cannot exceed the gross billing amount.");
        }
        BigDecimal initialNet = money(grossBilling.subtract(deductions));
        BigDecimal returned = nonNegative(input.amountReturned(), "Amount returned");
        if (returned.compareTo(withholding) > 0) {
            throw new IllegalArgumentException("Returned withholding cannot exceed the withheld amount.");
        }
        return new Result(grossBilling, inputVat, vatExclusive, royaltyBase, royalty, withholdingBase,
                withholding, additionalWithholding, ewt, retention, deductions, initialNet, returned,
                money(withholding.subtract(returned)), money(initialNet.add(returned)));
    }

    private static BigDecimal base(Basis basis, BigDecimal billing, BigDecimal vatExclusive) {
        return (basis == null ? Basis.VAT_EXCLUSIVE : basis) == Basis.BILLING_AMOUNT ? billing : vatExclusive;
    }

    private static BigDecimal optionalPercentage(BigDecimal base, BigDecimal rate, String label) {
        return rate == null ? ZERO : percentage(base, rate(rate, label));
    }

    private static BigDecimal percentage(BigDecimal base, BigDecimal rate) {
        return money(base.multiply(rate).divide(HUNDRED, 8, RoundingMode.HALF_UP));
    }

    private static BigDecimal rate(BigDecimal value, String label) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0 || normalized.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(label + " must be between 0 and 100.");
        }
        return normalized;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
        return normalized;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
