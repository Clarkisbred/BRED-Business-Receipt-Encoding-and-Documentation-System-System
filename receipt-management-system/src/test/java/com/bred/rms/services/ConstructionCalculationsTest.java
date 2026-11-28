package com.bred.rms.services;

import com.bred.rms.models.ConstructionModels;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionCalculationsTest {
    @Test
    void exactBillingWithholdingCalculation() {
        ConstructionModels.BillingWithholdingCalculation result =
                ConstructionCalculations.billingWithholding(
                        new BigDecimal("1174587.71"), new BigDecimal("5"), new BigDecimal("2"));
        assertEquals(new BigDecimal("1048739.03"), result.vatExclusiveBase());
        assertEquals(new BigDecimal("52436.95"), result.finalVatWithholding());
        assertEquals(new BigDecimal("20974.78"), result.incomeTaxWithholding());
        assertEquals(new BigDecimal("73411.73"), result.totalWithholding());
        assertEquals(new BigDecimal("1101175.98"), result.netBilling());
    }

    @Test
    void billingWithholdingHandlesZeroAndDifferentRates() {
        ConstructionModels.BillingWithholdingCalculation zero =
                ConstructionCalculations.billingWithholding(BigDecimal.ZERO,
                        new BigDecimal("5"), new BigDecimal("2"));
        assertEquals(BigDecimal.ZERO.setScale(2), zero.vatExclusiveBase());
        assertEquals(BigDecimal.ZERO.setScale(2), zero.totalWithholding());
        assertEquals(BigDecimal.ZERO.setScale(2), zero.netBilling());

        ConstructionModels.BillingWithholdingCalculation different =
                ConstructionCalculations.billingWithholding(
                        new BigDecimal("112000.00"), new BigDecimal("10"), new BigDecimal("3"));
        assertEquals(new BigDecimal("100000.00"), different.vatExclusiveBase());
        assertEquals(new BigDecimal("10000.00"), different.finalVatWithholding());
        assertEquals(new BigDecimal("3000.00"), different.incomeTaxWithholding());
        assertEquals(new BigDecimal("99000.00"), different.netBilling());

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionCalculations.billingWithholding(
                        new BigDecimal("-1"), new BigDecimal("5"), new BigDecimal("2")));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionCalculations.billingWithholding(
                        new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("2")));
    }
    @Test
    void advanceRecoveryNeverExceedsBalance() {
        assertEquals(new BigDecimal("3.00"),
                ConstructionCalculations.capRecovery(new BigDecimal("5.00"), new BigDecimal("3.00")));
    }

    @Test
    void retentionTotalsAreHeldReleasedAndReceivable() {
        ConstructionModels.RetentionTotals totals = ConstructionCalculations.retentionTotals(
                new BigDecimal("100.00"), new BigDecimal("35.00"));
        assertEquals(new BigDecimal("100.00"), totals.held());
        assertEquals(new BigDecimal("35.00"), totals.released());
        assertEquals(new BigDecimal("65.00"), totals.receivable());
    }

    @Test
    void liquidatedDamagesUseCapAndZeroDelay() {
        LocalDate revised = LocalDate.of(2026, 10, 1);
        ConstructionModels.LiquidatedDamages capped = ConstructionCalculations.liquidatedDamages(
                revised, LocalDate.of(2026, 10, 11),
                new BigDecimal("200.00"), new BigDecimal("1500.00"));
        assertEquals(10, capped.delayDays());
        assertEquals(new BigDecimal("1500.00"), capped.amount());
        assertTrue(capped.capped());

        ConstructionModels.LiquidatedDamages zero = ConstructionCalculations.liquidatedDamages(
                revised, revised.minusDays(1),
                new BigDecimal("200.00"), null);
        assertEquals(0, zero.delayDays());
        assertEquals(BigDecimal.ZERO.setScale(2), zero.amount());
    }

    @Test
    void approvedTimeExtensionChangesCompletionOnlyWhenApproved() {
        LocalDate original = LocalDate.of(2026, 12, 31);
        assertNull(ConstructionCalculations.approvedRevisedCompletion(
                original, LocalDate.of(2026, 11, 1), false, 30));
        assertEquals(LocalDate.of(2027, 1, 30),
                ConstructionCalculations.approvedRevisedCompletion(
                        original, LocalDate.of(2026, 11, 1), true, 30));
    }

    @Test
    void withholdingTaxReconcilesCashAndCertificateAmount() {
        assertTrue(ConstructionCalculations.withholdingReconciles(
                new BigDecimal("9000.00"), new BigDecimal("1000.00"), new BigDecimal("10000.00")));
        assertFalse(ConstructionCalculations.withholdingReconciles(
                new BigDecimal("8000.00"), new BigDecimal("1000.00"), new BigDecimal("10000.00")));
    }

    @Test
    void ageingBucketsAndOverdueText() {
        LocalDate today = LocalDate.of(2026, 11, 27);
        ConstructionModels.AgeingBilling over90 = ConstructionCalculations.ageingBilling(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15), today);
        assertEquals("Over 90", over90.bucket());
        assertTrue(over90.overdue());
        assertTrue(over90.daysOverdue() > 60);
    }

    @Test
    void subcontractBalanceIncludesCertifiedPaidAndRetention() {
        ConstructionModels.SubcontractBalance balance = ConstructionCalculations.subcontractBalance(
                new BigDecimal("50000.00"), new BigDecimal("30000.00"),
                new BigDecimal("20000.00"), new BigDecimal("3000.00"));
        assertEquals(new BigDecimal("7000.00"), balance.balance());
        assertEquals(new BigDecimal("3000.00"), balance.retentionHeld());
    }

    @Test
    void cashAdvanceLiquidationRequiresAuthorizedReasonWhenOutstanding() {
        assertTrue(ConstructionCalculations.canLiquidateAdvance(
                new BigDecimal("0.00"), false, null));
        assertFalse(ConstructionCalculations.canLiquidateAdvance(
                new BigDecimal("100.00"), false, null));
        assertTrue(ConstructionCalculations.canLiquidateAdvance(
                new BigDecimal("100.00"), true, "Final closeout approved"));
    }

    @Test
    void expiryThresholdLabelsExpiredAndExpiringSoon() {
        LocalDate today = LocalDate.of(2026, 11, 27);
        assertEquals(ConstructionModels.ExpiryState.EXPIRED,
                ConstructionCalculations.expiryState(today.minusDays(1), today, 60));
        assertEquals(ConstructionModels.ExpiryState.EXPIRING_SOON,
                ConstructionCalculations.expiryState(today.plusDays(30), today, 60));
        assertEquals(ConstructionModels.ExpiryState.CURRENT,
                ConstructionCalculations.expiryState(today.plusDays(61), today, 60));
    }

    @Test
    void zeroBudgetShowsNAAndVarianceUsesPercentage() {
        ConstructionModels.CostVariance zero = ConstructionCalculations.costVariance(
                BigDecimal.ZERO, new BigDecimal("100.00"));
        assertEquals("N/A", zero.status());
        assertNull(zero.variancePercent());

        ConstructionModels.CostVariance over = ConstructionCalculations.costVariance(
                new BigDecimal("1000.00"), new BigDecimal("1250.00"));
        assertEquals(new BigDecimal("250.00"), over.variancePeso());
        assertEquals(new BigDecimal("25.0000"), over.variancePercent());
        assertEquals("Over budget", over.status());
    }

    @Test
    void estimateBreakdownAndDerivedDates() {
        assertEquals(new BigDecimal("550.00"),
                ConstructionCalculations.costEstimateItemTotal(
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("50"),
                        new BigDecimal("50")));
        assertEquals(LocalDate.of(2027, 1, 10),
                ConstructionCalculations.derivedCompletionDate(LocalDate.of(2026, 11, 26), 45));
    }

    @Test
    void taxQuarterAndOfflineIdempotency() {
        assertEquals("2026-Q4",
                ConstructionCalculations.taxQuarter(LocalDate.of(2026, 11, 27)));
        java.util.Set<String> queued = new java.util.HashSet<>();
        assertTrue(ConstructionCalculations.offlineQueueKeyIsUnique(queued, "receipt-123"));
        queued.add("receipt-123");
        assertFalse(ConstructionCalculations.offlineQueueKeyIsUnique(queued, "receipt-123"));
    }

    @Test
    void retentionReleaseRequiresCloseoutOrRecordedOverride() {
        assertTrue(ConstructionCalculations.retentionReleaseAllowed(true, false, null));
        assertFalse(ConstructionCalculations.retentionReleaseAllowed(false, false, null));
        assertTrue(ConstructionCalculations.retentionReleaseAllowed(
                false, true, "Authorized closeout exception"));
    }

    @Test
    void boqAndVariationCalculationsPreserveContractControls() {
        assertEquals(new BigDecimal("12500.00"),
                ConstructionCalculations.boqLineAmount(new BigDecimal("250"), new BigDecimal("50")));
        assertEquals(new BigDecimal("102500.00"),
                ConstructionCalculations.approvedVariationAmount(
                        new BigDecimal("100000.00"), new BigDecimal("2500.00"), true));
        assertEquals(new BigDecimal("100000.00"),
                ConstructionCalculations.approvedVariationAmount(
                        new BigDecimal("100000.00"), new BigDecimal("2500.00"), false));
        assertEquals(new BigDecimal("103000.00"),
                ConstructionCalculations.projectCostAdjustment(
                        new BigDecimal("100000.00"), new BigDecimal("3000.00"), true));
    }

    @Test
    void progressUsesOnlyApprovedAccomplishments() {
        ConstructionModels.ProgressStatus status = ConstructionCalculations.progressStatus(
                new BigDecimal("50"), new BigDecimal("40"), false);
        assertEquals(BigDecimal.ZERO.setScale(4), status.approvedActual());
        assertEquals("Behind schedule", status.status());

        ConstructionModels.ProgressStatus approved = ConstructionCalculations.progressStatus(
                new BigDecimal("50"), new BigDecimal("55"), true);
        assertEquals(new BigDecimal("55.0000"), approved.approvedActual());
        assertEquals("Ahead of plan", approved.status());
    }

    @Test
    void billingNetReceivableAndSubmissionControls() {
        ConstructionModels.BillingPosition position = ConstructionCalculations.billingPosition(
                new BigDecimal("100000"), new BigDecimal("10000"),
                new BigDecimal("5000"), new BigDecimal("2000"),
                new BigDecimal("1000"), new BigDecimal("50000"));
        assertEquals(new BigDecimal("82000.00"), position.netPayable());
        assertEquals(new BigDecimal("32000.00"), position.receivable());

        ConstructionModels.BillingSubmissionCheck blocked =
                ConstructionCalculations.billingSubmissionCheck(
                        java.util.List.of("SOWA", "Accomplishment Report"),
                        java.util.List.of("SOWA"), false, null);
        assertFalse(blocked.canSubmit());
        assertEquals(java.util.List.of("Accomplishment Report"),
                blocked.missingAttachments());

        ConstructionModels.BillingSubmissionCheck overridden =
                ConstructionCalculations.billingSubmissionCheck(
                        java.util.List.of("SOWA"), java.util.List.of(),
                        true, "Authorized owner override");
        assertTrue(overridden.canSubmit());
    }

    @Test
    void profitLossAndCashFlowUsePesoTotals() {
        ConstructionModels.ProjectProfitLoss profit =
                ConstructionCalculations.projectProfitLoss(
                        new BigDecimal("100000"), new BigDecimal("65000"));
        assertEquals(new BigDecimal("35000.00"), profit.profit());
        assertEquals("Profit", profit.status());

        ConstructionModels.ProjectProfitLoss loss =
                ConstructionCalculations.projectProfitLoss(
                        new BigDecimal("50000"), new BigDecimal("70000"));
        assertEquals(new BigDecimal("-20000.00"), loss.profit());
        assertEquals("Loss", loss.status());

        ConstructionModels.CashFlow cash = ConstructionCalculations.cashFlow(
                new BigDecimal("10000"), new BigDecimal("50000"), new BigDecimal("35000"));
        assertEquals(new BigDecimal("25000.00"), cash.closing());
    }

    @Test
    void procurementAndStockRulesPreventNegativeMovements() {
        assertFalse(ConstructionCalculations.canPostStockMovement(
                "Issue", new BigDecimal("10"), new BigDecimal("12")));
        assertTrue(ConstructionCalculations.canPostStockMovement(
                "Issue", new BigDecimal("12"), new BigDecimal("10")));
        assertTrue(ConstructionCalculations.canPostStockMovement(
                "Purchase Receipt", new BigDecimal("0"), new BigDecimal("25")));
        assertEquals(new BigDecimal("500.00"),
                ConstructionCalculations.purchaseOrderRemaining(
                        new BigDecimal("2500.00"), new BigDecimal("2000.00")));
        ConstructionModels.StockPosition stock = ConstructionCalculations.stockPosition(
                new BigDecimal("40"), new BigDecimal("125.50"));
        assertEquals(new BigDecimal("5020.00"), stock.inventoryValue());
    }

    @Test
    void laborCostUsesConfiguredMultipliers() {
        ConstructionModels.LaborCost cost = ConstructionCalculations.laborCost(
                new BigDecimal("8"), new BigDecimal("2"), new BigDecimal("4"),
                new BigDecimal("100"), new BigDecimal("1.25"), new BigDecimal("2.00"));
        assertEquals(new BigDecimal("800.00"), cost.regularPay());
        assertEquals(new BigDecimal("250.00"), cost.overtimePay());
        assertEquals(new BigDecimal("800.00"), cost.holidayPay());
        assertEquals(new BigDecimal("1850.00"), cost.totalPay());
    }

    @Test
    void equipmentCostIsRentalFuelAndMaintenanceOnlyWhenApprovedRecordsExist() {
        assertEquals(new BigDecimal("3200.00"),
                ConstructionCalculations.equipmentCost(
                        new BigDecimal("2000"), new BigDecimal("700"), new BigDecimal("500")));
    }

    @Test
    void riskScoreAndMapCoordinatesAreValidated() {
        ConstructionModels.RiskScore critical = ConstructionCalculations.riskScore(5, 5);
        assertEquals(25, critical.score());
        assertEquals("Critical", critical.level());
        ConstructionCalculations.validateCoordinates(
                new BigDecimal("14.5995"), new BigDecimal("120.9842"));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionCalculations.validateCoordinates(
                        new BigDecimal("91"), new BigDecimal("120")));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionCalculations.riskScore(6, 3));
    }

    @Test
    void closeoutRequiresAllItemsAndNoOpenDeficiencies() {
        ConstructionModels.CloseoutStatus incomplete =
                ConstructionCalculations.closeoutStatus(10, 8, 0);
        assertFalse(incomplete.canClose());
        assertEquals("Incomplete closeout", incomplete.status());

        ConstructionModels.CloseoutStatus blocked =
                ConstructionCalculations.closeoutStatus(10, 10, 2);
        assertFalse(blocked.canClose());
        assertEquals("Open deficiencies", blocked.status());

        ConstructionModels.CloseoutStatus ready =
                ConstructionCalculations.closeoutStatus(10, 10, 0);
        assertTrue(ready.canClose());
        assertEquals("Ready for closeout", ready.status());
    }

    @Test
    void offlineSyncRequiresOnlineAuthorizedSessionAndIsIdempotent() {
        assertFalse(ConstructionCalculations.canSyncOfflineEntry(false, true, true, "Queued"));
        assertFalse(ConstructionCalculations.canSyncOfflineEntry(true, false, true, "Queued"));
        assertFalse(ConstructionCalculations.canSyncOfflineEntry(true, true, false, "Failed"));
        assertTrue(ConstructionCalculations.canSyncOfflineEntry(true, true, true, "Queued"));
        assertEquals("Synced",
                ConstructionCalculations.nextOfflineStatus("Queued", true));
        assertEquals("Failed",
                ConstructionCalculations.nextOfflineStatus("Syncing", false));
        assertEquals("Synced",
                ConstructionCalculations.nextOfflineStatus("Synced", false));
    }
}
