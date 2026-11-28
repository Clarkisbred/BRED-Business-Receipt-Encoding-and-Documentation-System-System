package com.bred.rms.models;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

/** Small construction-domain value objects used by the priority feature batch. */
public final class ConstructionModels {
    private ConstructionModels() {}

    public enum ExpiryState { EXPIRED, EXPIRING_SOON, CURRENT }

    public record ExpiryAlert(UUID id, String category, String name, LocalDate expiryDate,
                              ExpiryState state, String label) {}

    public record AdvanceRecovery(BigDecimal advance, BigDecimal recovered, BigDecimal unrecovered) {}

    public record RetentionTotals(BigDecimal held, BigDecimal released, BigDecimal receivable) {}

    public record SubcontractBalance(BigDecimal commitment, BigDecimal certified,
                                     BigDecimal paid, BigDecimal retentionHeld, BigDecimal balance) {}

    public record EquipmentCost(BigDecimal rental, BigDecimal fuel, BigDecimal maintenance, BigDecimal total) {}

    public record LiquidatedDamages(int delayDays, BigDecimal amount, boolean capped) {}

    public record AgeingBilling(int daysSinceSubmission, int daysOverdue,
                                String bucket, boolean overdue) {}

    public record CostVariance(BigDecimal budget, BigDecimal actual, BigDecimal variancePeso,
                               BigDecimal variancePercent, String status) {}

    public record ProgressStatus(BigDecimal planned, BigDecimal approvedActual,
                                 BigDecimal variancePercent, String status) {}

    public record BillingPosition(BigDecimal gross, BigDecimal retentionHeld,
                                  BigDecimal advanceRecovery, BigDecimal deductions,
                                  BigDecimal withholding, BigDecimal netPayable,
                                  BigDecimal collected, BigDecimal receivable) {}

    public record BillingSubmissionCheck(boolean canSubmit, List<String> missingAttachments) {}

    public record BillingWithholdingCalculation(BigDecimal billingAmount,
                                                BigDecimal vatExclusiveBase,
                                                BigDecimal finalVatWithholding,
                                                BigDecimal incomeTaxWithholding,
                                                BigDecimal totalWithholding,
                                                BigDecimal netBilling) {}

    public record ProjectProfitLoss(BigDecimal revenue, BigDecimal expenses,
                                    BigDecimal profit, BigDecimal marginPercent, String status) {}

    public record CashFlow(BigDecimal opening, BigDecimal collections,
                           BigDecimal payments, BigDecimal closing) {}

    public record StockPosition(BigDecimal quantity, BigDecimal weightedCost,
                                BigDecimal inventoryValue) {}

    public record LaborCost(BigDecimal regularPay, BigDecimal overtimePay,
                            BigDecimal holidayPay, BigDecimal totalPay) {}

    public record RiskScore(int probability, int impact, int score, String level) {}

    public record CloseoutStatus(int requiredItems, int completedItems,
                                 int openDeficiencies, boolean canClose,
                                 String status) {}
}
