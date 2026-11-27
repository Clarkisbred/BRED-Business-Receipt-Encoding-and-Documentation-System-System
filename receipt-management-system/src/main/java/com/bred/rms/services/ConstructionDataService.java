package com.bred.rms.services;

import com.bred.rms.models.ConstructionModels.ExpiryAlert;
import com.bred.rms.models.ConstructionModels.ExpiryState;
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
            case "Equipment costs" -> amountReport("equipment", "name", "rate", "Equipment rate");
            case "Cash advances" -> amountReport("cash_advances", "issued_to", "amount", "Cash advance");
            case "Tax calendar" -> simpleStatusReport("tax_calendar_items", "name", "status", null);
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
            default -> List.of();
        };
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
}
