package com.bred.rms.api;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptMath;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DashboardAnalytics {
    private DashboardAnalytics() { }

    static LocalApiServer.DashboardData build(
            List<Receipt> receipts,
            List<Project> projects,
            int supplierCount,
            List<JsonNode> billings,
            List<JsonNode> boqItems,
            List<JsonNode> payables,
            List<JsonNode> collections,
            List<JsonNode> payments,
            YearMonth currentMonth) {
        Map<YearMonth, MonthlyTotals> months = new LinkedHashMap<>();
        Map<String, BigDecimal> expensesByType = new HashMap<>();
        Map<UUID, Integer> receiptCounts = new HashMap<>();
        Map<UUID, BigDecimal> receiptSpend = new HashMap<>();
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal monthSpend = BigDecimal.ZERO;
        int monthReceiptCount = 0;

        for (Receipt receipt : receipts) {
            BigDecimal amount = receipt.grandTotal();
            totalExpenses = totalExpenses.add(amount);
            if (receipt.date() != null) {
                YearMonth month = YearMonth.from(receipt.date());
                MonthlyTotals totals = months.computeIfAbsent(month, ignored -> new MonthlyTotals());
                totals.expenses = totals.expenses.add(amount);
                if (month.equals(currentMonth)) {
                    monthSpend = monthSpend.add(amount);
                    monthReceiptCount++;
                }
            }
            String type = receipt.type() == null ? "Other" : receipt.type().label();
            expensesByType.merge(type, amount, BigDecimal::add);
            if (receipt.projectId() != null) {
                receiptCounts.merge(receipt.projectId(), 1, Integer::sum);
                receiptSpend.merge(receipt.projectId(), amount, BigDecimal::add);
            }
        }

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal outstandingReceivables = BigDecimal.ZERO;
        for (JsonNode billing : billings) {
            BigDecimal gross = decimal(billing, "gross_amount");
            totalRevenue = totalRevenue.add(gross);
            YearMonth month = month(billing, "billing_date");
            if (month != null) {
                MonthlyTotals totals = months.computeIfAbsent(month, ignored -> new MonthlyTotals());
                totals.revenue = totals.revenue.add(gross);
            }
            BigDecimal outstanding = decimal(billing, "net_amount")
                    .subtract(decimal(billing, "collected_amount"));
            if (outstanding.signum() > 0) outstandingReceivables = outstandingReceivables.add(outstanding);
        }

        BigDecimal outstandingPayables = outstandingPayables(payables);
        boolean cashFlowAvailable = false;
        for (JsonNode collection : collections) {
            YearMonth month = month(collection, "collection_date");
            if (month == null) continue;
            MonthlyTotals totals = months.computeIfAbsent(month, ignored -> new MonthlyTotals());
            totals.cashFlow = totals.cashFlow.add(decimal(collection, "amount"));
            cashFlowAvailable = true;
        }
        for (JsonNode payment : payments) {
            if (!payment.path("approved").asBoolean(false)) continue;
            YearMonth month = month(payment, "payment_date");
            if (month == null) continue;
            MonthlyTotals totals = months.computeIfAbsent(month, ignored -> new MonthlyTotals());
            totals.cashFlow = totals.cashFlow.subtract(decimal(payment, "amount"));
            cashFlowAvailable = true;
        }

        List<LocalApiServer.ProjectSummary> projectSummaries = projects.stream()
                .map(project -> new LocalApiServer.ProjectSummary(project.id().toString(), project.name(),
                        project.code() == null ? "" : project.code(),
                        receiptCounts.getOrDefault(project.id(), 0),
                        money(receiptSpend.getOrDefault(project.id(), BigDecimal.ZERO))))
                .toList();

        Map<UUID, BigDecimal[]> costsByProject = new LinkedHashMap<>();
        for (JsonNode item : boqItems) {
            UUID projectId = uuid(item, "project_id");
            if (projectId == null) continue;
            BigDecimal[] costs = costsByProject.computeIfAbsent(projectId,
                    ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            costs[0] = costs[0].add(decimal(item, "estimated_cost"));
            costs[1] = costs[1].add(decimal(item, "actual_cost"));
        }
        Map<UUID, String> projectNames = new HashMap<>();
        projects.forEach(project -> projectNames.put(project.id(), project.name()));
        List<LocalApiServer.ProjectMetric> projectMetrics = costsByProject.entrySet().stream()
                .map(entry -> {
                    BigDecimal budget = entry.getValue()[0];
                    BigDecimal actual = entry.getValue()[1];
                    BigDecimal progress = budget.signum() == 0 ? BigDecimal.ZERO
                            : actual.multiply(new BigDecimal("100")).divide(budget, 2, RoundingMode.HALF_UP);
                    return new LocalApiServer.ProjectMetric(entry.getKey().toString(),
                            projectNames.getOrDefault(entry.getKey(), "Project " + entry.getKey()),
                            money(budget), money(actual), money(progress));
                })
                .sorted(Comparator.comparing(LocalApiServer.ProjectMetric::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<LocalApiServer.MonthlyMetric> monthly = months.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    MonthlyTotals totals = entry.getValue();
                    BigDecimal profit = totals.revenue.subtract(totals.expenses);
                    return new LocalApiServer.MonthlyMetric(entry.getKey().toString(), money(totals.revenue),
                            money(totals.expenses), money(profit), money(totals.cashFlow));
                })
                .toList();
        List<LocalApiServer.ExpenseMetric> expenseBreakdown = expensesByType.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(entry -> new LocalApiServer.ExpenseMetric(entry.getKey(), money(entry.getValue())))
                .toList();

        BigDecimal netProfit = totalRevenue.subtract(totalExpenses);
        return new LocalApiServer.DashboardData(
                money(totalExpenses), money(monthSpend), monthReceiptCount, receipts.size(), projects.size(),
                List.copyOf(projectSummaries), money(totalRevenue), money(totalExpenses), money(netProfit),
                money(outstandingPayables), money(outstandingReceivables), supplierCount,
                List.copyOf(monthly), List.copyOf(expenseBreakdown), List.copyOf(projectMetrics), cashFlowAvailable);
    }

    private static BigDecimal outstandingPayables(List<JsonNode> payables) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode payable : payables) {
            if (!payable.path("approved").asBoolean(false)) continue;
            String status = payable.path("status").asText("");
            if ("Paid".equalsIgnoreCase(status) || "Cancelled".equalsIgnoreCase(status)) continue;
            BigDecimal outstanding = decimal(payable, "gross_amount").subtract(decimal(payable, "paid_amount"));
            if (outstanding.signum() > 0) total = total.add(outstanding);
        }
        return total;
    }

    private static YearMonth month(JsonNode row, String field) {
        String date = row.path(field).asText("");
        return date.isBlank() ? null : YearMonth.from(LocalDate.parse(date));
    }

    private static UUID uuid(JsonNode row, String field) {
        String value = row.path(field).asText("");
        return value.isBlank() ? null : UUID.fromString(value);
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Dashboard data contains an invalid amount in " + field, exception);
        }
    }

    private static String money(BigDecimal amount) {
        return ReceiptMath.money(amount).toPlainString();
    }

    private static final class MonthlyTotals {
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal expenses = BigDecimal.ZERO;
        private BigDecimal cashFlow = BigDecimal.ZERO;
    }
}
