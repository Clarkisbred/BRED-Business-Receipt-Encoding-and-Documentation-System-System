package com.bred.rms.services;

import com.bred.rms.models.ConstructionModels.AdvanceRecovery;
import com.bred.rms.models.ConstructionModels.AgeingBilling;
import com.bred.rms.models.ConstructionModels.CostVariance;
import com.bred.rms.models.ConstructionModels.ExpiryState;
import com.bred.rms.models.ConstructionModels.LiquidatedDamages;
import com.bred.rms.models.ConstructionModels.RetentionTotals;
import com.bred.rms.models.ConstructionModels.SubcontractBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure, unit-tested rules for construction financial controls. */
public final class ConstructionCalculations {
    private ConstructionCalculations() {}

    public static BigDecimal capRecovery(BigDecimal requested, BigDecimal unrecovered) {
        BigDecimal r = money(requested);
        BigDecimal b = money(unrecovered);
        if (r.signum() < 0 || b.signum() < 0) throw new IllegalArgumentException("Amounts cannot be negative.");
        return r.min(b);
    }

    public static AdvanceRecovery advanceRecovery(BigDecimal advance, BigDecimal recovered) {
        BigDecimal a = money(advance);
        BigDecimal r = money(recovered);
        if (a.signum() < 0 || r.signum() < 0) throw new IllegalArgumentException("Amounts cannot be negative.");
        if (r.compareTo(a) > 0) r = a;
        return new AdvanceRecovery(a, r, a.subtract(r));
    }

    public static RetentionTotals retentionTotals(BigDecimal held, BigDecimal released) {
        BigDecimal h = money(held);
        BigDecimal r = money(released);
        if (h.signum() < 0 || r.signum() < 0) throw new IllegalArgumentException("Amounts cannot be negative.");
        if (r.compareTo(h) > 0) r = h;
        return new RetentionTotals(h, r, h.subtract(r));
    }

    public static LiquidatedDamages liquidatedDamages(LocalDate revisedCompletion, LocalDate completion,
                                                       BigDecimal ratePerDay, BigDecimal cap) {
        if (revisedCompletion == null) throw new IllegalArgumentException("Revised completion date is required.");
        LocalDate end = completion == null ? LocalDate.now() : completion;
        long raw = ChronoUnit.DAYS.between(revisedCompletion, end);
        int delay = raw > 0 ? (int) Math.min(raw, Integer.MAX_VALUE) : 0;
        BigDecimal amount = money(ratePerDay).multiply(BigDecimal.valueOf(delay));
        boolean capped = cap != null && amount.compareTo(money(cap)) > 0;
        if (capped) amount = money(cap);
        return new LiquidatedDamages(delay, amount, capped);
    }

    public static LocalDate approvedRevisedCompletion(LocalDate original, LocalDate approvedDate,
                                                       boolean approved, int approvedDays) {
        if (!approved) return null;
        if (approvedDays < 0) throw new IllegalArgumentException("Approved days cannot be negative.");
        if (original == null) throw new IllegalArgumentException("Original completion date is required.");
        return original.plusDays(approvedDays);
    }

    public static AgeingBilling ageingBilling(LocalDate submitted, LocalDate due, LocalDate today) {
        if (submitted == null) throw new IllegalArgumentException("Submission date is required.");
        LocalDate asOf = today == null ? LocalDate.now() : today;
        int since = (int) Math.max(0, ChronoUnit.DAYS.between(submitted, asOf));
        int overdue = due == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(due, asOf));
        String bucket = since <= 30 ? "0-30"
                : since <= 60 ? "31-60"
                : since <= 90 ? "61-90" : "Over 90";
        return new AgeingBilling(since, overdue, bucket, overdue > 0);
    }

    public static SubcontractBalance subcontractBalance(BigDecimal commitment, BigDecimal certified,
                                     BigDecimal paid, BigDecimal retained) {
        BigDecimal c = money(commitment), cert = money(certified), p = money(paid), r = money(retained);
        if (c.signum() < 0 || cert.signum() < 0 || p.signum() < 0 || r.signum() < 0)
            throw new IllegalArgumentException("Amounts cannot be negative.");
        return new SubcontractBalance(c, cert, p, r, cert.subtract(p).subtract(r).max(BigDecimal.ZERO));
    }

    public static CostVariance costVariance(BigDecimal budget, BigDecimal actual) {
        BigDecimal b = money(budget), a = money(actual);
        BigDecimal variance = a.subtract(b);
        if (b.signum() == 0) return new CostVariance(b, a, variance, null, "N/A");
        BigDecimal pct = variance.divide(b.abs(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        String status = variance.signum() > 0 ? "Over budget" : variance.signum() < 0 ? "Under budget" : "On budget";
        return new CostVariance(b, a, variance, pct, status);
    }

    public static ExpiryState expiryState(LocalDate expiry, LocalDate today, int warningDays) {
        if (expiry == null) throw new IllegalArgumentException("Expiry date is required.");
        LocalDate asOf = today == null ? LocalDate.now() : today;
        if (expiry.isBefore(asOf)) return ExpiryState.EXPIRED;
        if (!expiry.isAfter(asOf.plusDays(Math.max(0, warningDays)))) return ExpiryState.EXPIRING_SOON;
        return ExpiryState.CURRENT;
    }

    public static boolean canLiquidateAdvance(BigDecimal remaining, boolean authorizedOverride, String reason) {
        if (remaining == null) throw new IllegalArgumentException("Remaining balance is required.");
        if (remaining.signum() == 0) return true;
        return authorizedOverride && reason != null && !reason.isBlank();
    }

    public static boolean withholdingReconciles(BigDecimal cashReceived, BigDecimal withholding,
                                                BigDecimal billingAmountDue) {
        return money(cashReceived).add(money(withholding)).compareTo(money(billingAmountDue)) == 0;
    }

    public static BigDecimal equipmentCost(BigDecimal rental, BigDecimal fuel, BigDecimal maintenance) {
        return money(rental).add(money(fuel)).add(money(maintenance));
    }

    public static BigDecimal costEstimateItemTotal(BigDecimal materials, BigDecimal labor,
                                                    BigDecimal equipment, BigDecimal overhead,
                                                    BigDecimal contingency, BigDecimal profit,
                                                    BigDecimal taxes) {
        return money(materials).add(money(labor)).add(money(equipment))
                .add(money(overhead)).add(money(contingency)).add(money(profit)).add(money(taxes));
    }

    public static String taxQuarter(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Period date is required.");
        int quarter = ((date.getMonthValue() - 1) / 3) + 1;
        return date.getYear() + "-Q" + quarter;
    }

    public static boolean offlineQueueKeyIsUnique(Set<String> existingKeys, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return false;
        return existingKeys == null || !existingKeys.contains(idempotencyKey);
    }

    public static Set<String> deduplicateQueueKeys(Set<String> keys) {
        return keys == null ? Set.of() : new LinkedHashSet<>(keys);
    }

    public static LocalDate derivedCompletionDate(LocalDate noticeToProceed, int contractDurationDays) {
        if (noticeToProceed == null) throw new IllegalArgumentException("Notice to Proceed is required.");
        if (contractDurationDays < 0) throw new IllegalArgumentException("Contract duration cannot be negative.");
        return noticeToProceed.plusDays(contractDurationDays);
    }

    public static boolean retentionReleaseAllowed(boolean closeoutPresent, boolean overrideAuthorized,
                                                   String overrideReason) {
        if (closeoutPresent) return true;
        return overrideAuthorized && overrideReason != null && !overrideReason.isBlank();
    }

    public static BigDecimal boqLineAmount(BigDecimal quantity, BigDecimal unitPrice) {
        if (quantity == null || unitPrice == null) throw new IllegalArgumentException("Quantity and unit price are required.");
        if (quantity.signum() < 0 || unitPrice.signum() < 0)
            throw new IllegalArgumentException("Quantity and unit price cannot be negative.");
        return money(quantity.multiply(unitPrice));
    }

    public static BigDecimal approvedVariationAmount(BigDecimal originalAmount,
                                                     BigDecimal approvedEffect,
                                                     boolean approved) {
        if (originalAmount == null) throw new IllegalArgumentException("Original contract amount is required.");
        if (approvedEffect == null) approvedEffect = BigDecimal.ZERO;
        if (approved) return money(originalAmount.add(approvedEffect));
        return money(originalAmount);
    }

    public static BigDecimal projectCostAdjustment(BigDecimal currentContractAmount,
                                                   BigDecimal variationAmount,
                                                   boolean approved) {
        if (currentContractAmount == null) throw new IllegalArgumentException("Contract amount is required.");
        if (!approved) return money(currentContractAmount);
        if (variationAmount == null) throw new IllegalArgumentException("Approved variation amount is required.");
        return money(currentContractAmount.add(variationAmount));
    }

    public static com.bred.rms.models.ConstructionModels.ProgressStatus progressStatus(
            BigDecimal planned, BigDecimal actual, boolean accomplishmentApproved) {
        BigDecimal p = normalizePercent(planned);
        BigDecimal a = accomplishmentApproved ? normalizePercent(actual) : BigDecimal.ZERO.setScale(4);
        BigDecimal variance = a.subtract(p);
        String status = variance.signum() < 0 ? "Behind schedule"
                : variance.signum() > 0 ? "Ahead of plan" : "On schedule";
        return new com.bred.rms.models.ConstructionModels.ProgressStatus(p, a, variance, status);
    }

    public static boolean approvedAccomplishment(boolean approved, BigDecimal progressPercent) {
        if (progressPercent == null) throw new IllegalArgumentException("Progress percent is required.");
        if (progressPercent.signum() < 0 || progressPercent.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("Progress percent must be between 0 and 100.");
        return approved;
    }

    private static BigDecimal normalizePercent(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("Progress percent must be between 0 and 100.");
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    public static com.bred.rms.models.ConstructionModels.BillingPosition billingPosition(
            BigDecimal gross, BigDecimal retentionHeld, BigDecimal advanceRecovery,
            BigDecimal deductions, BigDecimal withholding, BigDecimal collected) {
        BigDecimal g = money(gross), r = money(retentionHeld), a = money(advanceRecovery);
        BigDecimal d = money(deductions), w = money(withholding), c = money(collected);
        if (g.signum() < 0 || r.signum() < 0 || a.signum() < 0 || d.signum() < 0
                || w.signum() < 0 || c.signum() < 0)
            throw new IllegalArgumentException("Billing amounts cannot be negative.");
        if (a.compareTo(g) > 0) a = g;
        BigDecimal net = g.subtract(r).subtract(a).subtract(d).subtract(w);
        if (net.signum() < 0) net = BigDecimal.ZERO.setScale(2);
        if (c.compareTo(net) > 0) c = net;
        return new com.bred.rms.models.ConstructionModels.BillingPosition(
                g, r, a, d, w, net, c, net.subtract(c));
    }

    public static com.bred.rms.models.ConstructionModels.BillingSubmissionCheck billingSubmissionCheck(
            java.util.List<String> requiredAttachments,
            java.util.List<String> suppliedAttachments,
            boolean overrideAuthorized,
            String overrideReason) {
        java.util.Set<String> supplied = suppliedAttachments == null
                ? java.util.Set.of()
                : suppliedAttachments.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toSet());
        java.util.List<String> missing = requiredAttachments == null ? java.util.List.of()
                : requiredAttachments.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .filter(v -> !supplied.contains(v.trim().toLowerCase()))
                    .distinct().toList();
        boolean override = overrideAuthorized && overrideReason != null && !overrideReason.isBlank();
        return new com.bred.rms.models.ConstructionModels.BillingSubmissionCheck(
                missing.isEmpty() || override, missing);
    }

    public static com.bred.rms.models.ConstructionModels.ProjectProfitLoss projectProfitLoss(
            BigDecimal revenue, BigDecimal expenses) {
        BigDecimal r = money(revenue), e = money(expenses);
        if (r.signum() < 0 || e.signum() < 0)
            throw new IllegalArgumentException("P&L amounts cannot be negative.");
        BigDecimal profit = r.subtract(e);
        BigDecimal margin = r.signum() == 0 ? null
                : profit.divide(r, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        String status = profit.signum() > 0 ? "Profit" : profit.signum() < 0 ? "Loss" : "Break-even";
        return new com.bred.rms.models.ConstructionModels.ProjectProfitLoss(
                r, e, profit, margin, status);
    }

    public static com.bred.rms.models.ConstructionModels.CashFlow cashFlow(
            BigDecimal opening, BigDecimal collections, BigDecimal payments) {
        BigDecimal o = money(opening), c = money(collections), p = money(payments);
        if (c.signum() < 0 || p.signum() < 0)
            throw new IllegalArgumentException("Collections and payments cannot be negative.");
        return new com.bred.rms.models.ConstructionModels.CashFlow(
                o, c, p, o.add(c).subtract(p));
    }

    public static boolean canPostStockMovement(String movementType, BigDecimal availableQuantity,
                                               BigDecimal movementQuantity) {
        if (movementType == null || movementType.isBlank())
            throw new IllegalArgumentException("Movement type is required.");
        if (movementQuantity == null || movementQuantity.signum() <= 0)
            throw new IllegalArgumentException("Movement quantity must be greater than zero.");
        if (List.of("Issue","Transfer Out","Return to Supplier").contains(movementType)
                && (availableQuantity == null || availableQuantity.compareTo(movementQuantity) < 0))
            return false;
        return true;
    }

    public static com.bred.rms.models.ConstructionModels.StockPosition stockPosition(
            BigDecimal quantity, BigDecimal weightedCost) {
        BigDecimal q = quantity == null ? BigDecimal.ZERO : quantity;
        BigDecimal c = weightedCost == null ? BigDecimal.ZERO : weightedCost;
        if (q.signum() < 0 || c.signum() < 0)
            throw new IllegalArgumentException("Stock quantity and cost cannot be negative.");
        return new com.bred.rms.models.ConstructionModels.StockPosition(
                q.setScale(4, RoundingMode.HALF_UP),
                money(c),
                money(q.multiply(c)));
    }

    public static BigDecimal purchaseOrderRemaining(BigDecimal ordered, BigDecimal received) {
        BigDecimal o = money(ordered), r = money(received);
        if (o.signum() < 0 || r.signum() < 0)
            throw new IllegalArgumentException("PO amounts cannot be negative.");
        return r.compareTo(o) >= 0 ? BigDecimal.ZERO.setScale(2) : o.subtract(r);
    }

    private static BigDecimal money(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
