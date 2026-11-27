package com.bred.rms.services;

import com.bred.rms.models.ConstructionModels.ExpiryAlert;
import com.bred.rms.models.ConstructionModels.ExpiryState;
import com.bred.rms.models.ConstructionModels;
import com.bred.rms.supabase.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.supabase.SupabaseException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;

/** Read-only construction alerts for the dashboard. Mutating workflows require authorized UI flows. */
public final class ConstructionDataService {
    public record ConstructionReportRow(String label, int count, String status, BigDecimal amount) {}
    public record ConstructionSummary(BigDecimal grossBillings, BigDecimal collected,
                                      BigDecimal receivable, int overdueBillings) {}
    public record BOQCostSummary(BigDecimal estimated, BigDecimal actual, BigDecimal variance,
                                 BigDecimal variancePercent, String status) {}
    private final RestClient rest;
    private final int warningDays;

    public ConstructionDataService(RestClient rest) {
        this(rest, 60);
    }

    public ConstructionDataService(RestClient rest, int warningDays) {
        this.rest = rest;
        this.warningDays = warningDays;
    }

    public List<ExpiryAlert> expiryAlerts() {
        if (rest == null) return List.of();
        List<ExpiryAlert> alerts = new ArrayList<>();
        readExpiryAlerts(alerts, "company_licenses", "License",
                "license_type", "license_number");
        readExpiryAlerts(alerts, "company_permits", "Permit",
                "permit_type", "reference_number");
        readExpiryAlerts(alerts, "project_securities", "Bond",
                "security_type", "reference_number");
        readExpiryAlerts(alerts, "project_insurance", "Insurance",
                "insurance_type", "reference_number");
        readExpiryAlerts(alerts, "project_warranties", "Warranty",
                "warranty_type", "warranty_type");
        return alerts.stream()
                .filter(a -> a.state() != ExpiryState.CURRENT)
                .sorted(java.util.Comparator.comparing(ExpiryAlert::expiryDate))
                .toList();
    }

    public int failedTestCount() {
        if (rest == null) return 0;
        return countRows("project_material_tests", "pass_fail", "Fail");
    }

    public int openSafetyCount() {
        if (rest == null) return 0;
        return countRows("safety_records", "status", "Open");
    }

    public ConstructionSummary summary() {
        if (rest == null) return new ConstructionSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal receivable = BigDecimal.ZERO;
        int overdue = 0;
        for (JsonNode row : rows("project_billings")) {
            BigDecimal net = decimal(row, "net_amount");
            BigDecimal paid = decimal(row, "collected_amount");
            gross = gross.add(decimal(row, "gross_amount"));
            collected = collected.add(paid);
            BigDecimal outstanding = net.subtract(paid);
            if (outstanding.signum() > 0) receivable = receivable.add(outstanding);
            String status = text(row, "status");
            if ("Overdue".equalsIgnoreCase(status)
                    || (outstanding.signum() > 0 && overdue(row))) overdue++;
        }
        return new ConstructionSummary(gross, collected, receivable, overdue);
    }

    public BOQCostSummary boqCostSummary() {
        if (rest == null) return new BOQCostSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, "N/A");
        BigDecimal estimated = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        try {
            for (JsonNode row : rest.selectAll("project_boq_cost_variance",
                    "select=estimated_total,actual_total,variance_peso,variance_percent,status")) {
                estimated = estimated.add(decimal(row, "estimated_total"));
                actual = actual.add(decimal(row, "actual_total"));
            }
            var cost = ConstructionCalculations.costVariance(estimated, actual);
            return new BOQCostSummary(cost.budget(), cost.actual(),
                    cost.variancePeso(), cost.variancePercent(), cost.status());
        } catch (SupabaseException | RuntimeException ignored) {
            // The aggregate view is unavailable until migration 035 is deployed.
        }
        estimated = BigDecimal.ZERO;
        actual = BigDecimal.ZERO;
        for (JsonNode row : rows("project_boq_items")) {
            estimated = estimated.add(decimal(row, "estimated_cost"));
            actual = actual.add(decimal(row, "actual_cost"));
        }
        var cost = ConstructionCalculations.costVariance(estimated, actual);
        return new BOQCostSummary(cost.budget(), cost.actual(),
                cost.variancePeso(), cost.variancePercent(), cost.status());
    }

    private boolean overdue(JsonNode row) {
        JsonNode due = row.get("due_date");
        if (due == null || due.isNull()) return false;
        try {
            return LocalDate.parse(due.asText()).isBefore(LocalDate.now());
        } catch (RuntimeException error) {
            return false;
        }
    }

    public List<JsonNode> rows(String table) {
        if (rest == null) return List.of();
        try {
            return rest.selectAll(table, "select=*");
        } catch (SupabaseException error) {
            throw new IllegalStateException("Construction records could not be loaded: " + error.getMessage(), error);
        }
    }

    public void saveRow(String table, UUID id, Map<String, Object> values) {
        if (rest == null) throw new IllegalStateException("Construction CRUD requires the Supabase-backed app.");
        Map<String, Object> row = new LinkedHashMap<>(values);
        row.entrySet().removeIf(entry -> entry.getValue() == null
                || (entry.getValue() instanceof String s && s.isBlank()));
        if (id == null) {
            try {
                rest.insert(table, row);
            } catch (SupabaseException error) {
                throw new IllegalStateException("Construction record could not be added: " + error.getMessage(), error);
            }
        } else {
            try {
                rest.patch(table, "id=eq." + id, row);
            } catch (SupabaseException error) {
                throw new IllegalStateException("Construction record could not be updated: " + error.getMessage(), error);
            }
        }
    }

    public void deleteRow(String table, UUID id) {
        if (rest == null) throw new IllegalStateException("Construction CRUD requires the Supabase-backed app.");
        if (id == null) throw new IllegalArgumentException("Select a record to delete.");
        try {
            rest.delete(table, "id=eq." + id);
        } catch (SupabaseException error) {
            throw new IllegalStateException("Construction record could not be deleted: " + error.getMessage(), error);
        }
    }

    public List<ConstructionReportRow> constructionReport(String report) {
        if (rest == null) return List.of();
        return switch (report) {
            case "Compliance expiry" -> expiryReport();
            case "Failed tests" -> simpleStatusReport("project_material_tests", "test_type",
                    "pass_fail", "Fail");
            case "Open safety" -> simpleStatusReport("safety_records", "description",
                    "status", "Open");
            case "Bid summary" -> simpleStatusReport("bids", "bid_reference", "result", null);
            case "Subcontract balances" -> amountReport("subcontracts", "scope",
                    "subcontract_amount", "Subcontract commitment");
            case "Equipment costs" -> amountReport("equipment_cost_postings", "equipment_id",
                    "total_cost", "Equipment cost");
            case "Project risks" -> simpleStatusReport("project_risks", "title", "level", null);
            case "Project map" -> simpleStatusReport("project_map_markers", "marker_name", "status", null);
            case "Quality NCR" -> simpleStatusReport("project_quality_ncr", "ncr_number", "status", null);
            case "Punch items" -> simpleStatusReport("project_punch_items", "punch_number", "status", null);
            case "Closeout" -> simpleStatusReport("project_handover_records", "certificate_number", "status", null);
            case "Cash advances" -> amountReport("cash_advances", "issued_to", "amount", "Cash advance");
            case "Tax calendar" -> taxCalendarReport();
            case "Billing aging" -> billingAgingReport();
            case "Progress and accomplishments" -> simpleStatusReport("project_accomplishments",
                    "remarks", "approved", null);
            case "Milestones" -> simpleStatusReport("project_milestones", "milestone_name", "status", null);
            case "Billing position" -> simpleStatusReport("project_billings", "billing_number", "status", null);
            case "Collections" -> simpleStatusReport("project_billing_collections",
                    "reference_number", "bank_reference", null);
            case "Overdue billings" -> simpleStatusReport("project_billings", "billing_number", "status", "Overdue");
            case "Project P&L" -> simpleStatusReport("project_pnl_snapshots", "status", "status", null);
            case "Payables" -> simpleStatusReport("project_payables", "invoice_number", "status", null);
            case "Cash flow" -> simpleStatusReport("project_cash_movements",
                    "movement_type", "approved", null);
            case "Procurement" -> simpleStatusReport("purchase_orders", "po_number", "status", null);
            case "Inventory stock" -> amountReport("material_stock", "description", "quantity", "Stock quantity");
            case "Equipment usage" -> simpleStatusReport("equipment_usage", "usage_date", "approved", null);
            case "BOQ cost variance" -> boqCostVarianceReport();
            default -> List.of();
        };
    }

    public List<ConstructionReportRow> taxCalendarReport() {
        List<ConstructionReportRow> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (JsonNode row : rows("tax_calendar_items")) {
            String name = text(row, "name");
            String frequency = text(row, "frequency");
            String rawStatus = text(row, "status");
            LocalDate due = parseDate(text(row, "due_date"));
            if (due == null) {
                result.add(new ConstructionReportRow(name, 1, rawStatus.isBlank() ? "N/A" : rawStatus,
                        BigDecimal.ZERO));
                continue;
            }
            String quarter = ConstructionCalculations.taxQuarter(due);
            String status = rawStatus;
            if (!"Filed".equalsIgnoreCase(rawStatus)
                    && !"Paid".equalsIgnoreCase(rawStatus)
                    && !"Cancelled".equalsIgnoreCase(rawStatus)) {
                if (due.isBefore(today)) status = "Overdue";
                else if (!due.isAfter(today.plusDays(30))) status = "Due soon";
                else status = "Scheduled";
            }
            String label = name + " · " + quarter
                    + (frequency.isBlank() ? "" : " · " + frequency);
            result.add(new ConstructionReportRow(label, 1, status, BigDecimal.ZERO));
        }
        return result;
    }

    public List<ConstructionReportRow> billingAgingReport() {
        List<ConstructionReportRow> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        List<JsonNode> agingRows;
        try {
            agingRows = rest == null ? List.of() : rest.selectAll("project_billing_aging",
                    "select=billing_number,submitted_date,due_date,status,net_amount,collected_amount,outstanding_amount");
        } catch (SupabaseException | RuntimeException ignored) {
            try {
                agingRows = rest == null ? List.of() : rest.selectAll("project_billings",
                        "select=billing_number,submitted_at,billing_date,due_date,status,net_amount,collected_amount");
            } catch (SupabaseException | RuntimeException fallbackError) {
                return List.of();
            }
        }
        for (JsonNode row : agingRows) {
            String billing = text(row, "billing_number");
            BigDecimal net = decimal(row, "net_amount");
            BigDecimal collected = decimal(row, "collected_amount");
            BigDecimal outstanding = row.hasNonNull("outstanding_amount")
                    ? decimal(row, "outstanding_amount")
                    : net.subtract(collected).max(BigDecimal.ZERO);
            if (outstanding.signum() == 0) continue;
            LocalDate submitted = parseDate(text(row, "submitted_date"));
            if (submitted == null) {
                submitted = parseDate(text(row, "submitted_at"));
            }
            if (submitted == null) {
                submitted = parseDate(text(row, "billing_date"));
            }
            LocalDate due = parseDate(text(row, "due_date"));
            if (submitted == null) submitted = due;
            if (submitted == null) {
                result.add(new ConstructionReportRow(billing, 1, "0-30", outstanding));
                continue;
            }
            ConstructionModels.AgeingBilling aging =
                    ConstructionCalculations.ageingBilling(submitted, due, today);
            result.add(new ConstructionReportRow(
                    billing + " · " + aging.daysOverdue() + " days overdue",
                    1, aging.bucket(), outstanding));
        }
        return result;
    }

    public List<ConstructionReportRow> boqCostVarianceReport() {
        if (rest == null) return List.of();
        Map<String, String> projectNames = new LinkedHashMap<>();
        try {
            for (JsonNode project : rest.selectAll("projects", "select=id,name")) {
                projectNames.put(text(project, "id"), text(project, "name"));
            }
        } catch (SupabaseException | RuntimeException ignored) {
            return List.of();
        }
        try {
            List<JsonNode> viewRows = rest.selectAll("project_boq_cost_variance",
                    "select=project_id,estimated_total,actual_total,variance_peso,variance_percent,status");
            List<ConstructionReportRow> result = new ArrayList<>();
            for (JsonNode row : viewRows) {
                String projectId = text(row, "project_id");
                result.add(new ConstructionReportRow(
                        projectNames.getOrDefault(projectId, "Project " + projectId),
                        1,
                        text(row, "status"),
                        decimal(row, "variance_peso")));
            }
            return result;
        } catch (SupabaseException | RuntimeException ignored) {
            Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonNode row : rows("project_boq_items")) {
                String projectId = text(row, "project_id");
                if (projectId.isBlank()) continue;
                BigDecimal[] values = totals.computeIfAbsent(projectId,
                        key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                values[0] = values[0].add(decimal(row, "estimated_cost"));
                values[1] = values[1].add(decimal(row, "actual_cost"));
                counts.merge(projectId, 1, Integer::sum);
            }
            List<ConstructionReportRow> result = new ArrayList<>();
            for (Map.Entry<String, BigDecimal[]> entry : totals.entrySet()) {
                var cost = ConstructionCalculations.costVariance(entry.getValue()[0], entry.getValue()[1]);
                result.add(new ConstructionReportRow(
                        projectNames.getOrDefault(entry.getKey(), "Project " + entry.getKey()),
                        counts.getOrDefault(entry.getKey(), 0),
                        cost.status(), cost.variancePeso()));
            }
            return result;
        }
    }

    private List<ConstructionReportRow> expiryReport() {
        List<ConstructionReportRow> result = new ArrayList<>();
        for (ExpiryAlert alert : expiryAlerts()) {
            result.add(new ConstructionReportRow(alert.category() + ": " + alert.name(),
                    1, alert.label(), BigDecimal.ZERO));
        }
        return result;
    }

    private List<ConstructionReportRow> simpleStatusReport(String table, String labelField,
                                                           String statusField, String statusFilter) {
        List<ConstructionReportRow> result = new ArrayList<>();
        for (JsonNode row : rows(table)) {
            String status = text(row, statusField);
            if (statusFilter != null && !statusFilter.equalsIgnoreCase(status)) continue;
            result.add(new ConstructionReportRow(text(row, labelField), 1,
                    status.isBlank() ? "N/A" : status, BigDecimal.ZERO));
        }
        return result;
    }

    private List<ConstructionReportRow> amountReport(String table, String labelField,
                                                     String amountField, String fallbackLabel) {
        List<ConstructionReportRow> result = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (JsonNode row : rows(table)) {
            String label = text(row, labelField);
            BigDecimal amount = decimal(row, amountField);
            total = total.add(amount);
            count++;
            result.add(new ConstructionReportRow(label.isBlank() ? fallbackLabel : label,
                    1, "Recorded", amount));
        }
        if (!result.isEmpty()) {
            result.add(new ConstructionReportRow("TOTAL " + fallbackLabel,
                    count, "Total", total));
        }
        return result;
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return BigDecimal.ZERO;
        try {
            return value.decimalValue();
        } catch (RuntimeException error) {
            return BigDecimal.ZERO;
        }
    }

    private void readExpiryAlerts(List<ExpiryAlert> out, String table, String category,
                                  String nameField, String referenceField) {
        try {
            for (JsonNode row : rest.selectAll(table,
                    "select=id," + nameField + "," + referenceField + ",valid_to,end_date")) {
                JsonNode expiryNode = row.hasNonNull("valid_to") ? row.get("valid_to")
                        : row.get("end_date");
                if (expiryNode == null || expiryNode.isNull()) continue;
                LocalDate expiry = LocalDate.parse(expiryNode.asText());
                ExpiryState state = ConstructionCalculations.expiryState(expiry, LocalDate.now(), warningDays);
                String name = text(row, nameField);
                String reference = text(row, referenceField);
                String label = state == ExpiryState.EXPIRED ? "EXPIRED"
                        : state == ExpiryState.EXPIRING_SOON ? "EXPIRING SOON" : "CURRENT";
                out.add(new ExpiryAlert(uuid(row), category,
                        name + (reference.isBlank() ? "" : " (" + reference + ")"),
                        expiry, state, label));
            }
        } catch (SupabaseException | RuntimeException ignored) {
            // A newly deployed client may briefly miss a migration; alert data remains fail-open.
        }
    }

    private int countRows(String table, String field, String value) {
        try {
            int count = 0;
            for (JsonNode row : rest.selectAll(table, "select=" + field)) {
                if (value.equalsIgnoreCase(text(row, field))) count++;
            }
            return count;
        } catch (SupabaseException | RuntimeException ignored) {
            return 0;
        }
    }

    private static UUID uuid(JsonNode row) {
        return row.hasNonNull("id") ? UUID.fromString(row.get("id").asText()) : null;
    }

    private static String text(JsonNode row, String field) {
        return row.hasNonNull(field) ? row.get(field).asText() : "";
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (value.length() > 10) return LocalDate.parse(value.substring(0, 10));
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
