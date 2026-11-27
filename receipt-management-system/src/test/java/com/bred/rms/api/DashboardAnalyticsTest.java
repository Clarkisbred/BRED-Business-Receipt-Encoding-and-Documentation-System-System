package com.bred.rms.api;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardAnalyticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void buildsChartsAndTotalsFromRecordedReceiptsAndConstructionRows() throws Exception {
        UUID projectId = UUID.fromString("22222222-0000-0000-0000-000000000001");
        Project project = new Project(projectId, "Bridge Alpha", "BA-01", "Public");
        List<Receipt> receipts = List.of(
                receipt("R-001", LocalDate.of(2026, 3, 5), ReceiptType.OFFICIAL_RECEIPT,
                        projectId, "100.00", "12.00"),
                receipt("R-002", LocalDate.of(2026, 3, 18), ReceiptType.CASH_INVOICE,
                        null, "50.00", "5.00"));
        List<JsonNode> billings = rows("""
                [
                  {"billing_date":"2026-03-14","gross_amount":300,"net_amount":280,"collected_amount":100},
                  {"billing_date":"2026-04-02","gross_amount":100,"net_amount":100,"collected_amount":0}
                ]
                """);
        List<JsonNode> boq = rows("""
                [{"project_id":"22222222-0000-0000-0000-000000000001",
                  "estimated_cost":1000,"actual_cost":1250}]
                """);
        List<JsonNode> payables = rows("""
                [
                  {"approved":true,"status":"Approved","gross_amount":80,"paid_amount":20},
                  {"approved":false,"status":"Submitted","gross_amount":500,"paid_amount":0}
                ]
                """);
        List<JsonNode> collections = rows("""
                [{"collection_date":"2026-03-20","amount":100}]
                """);
        List<JsonNode> payments = rows("""
                [
                  {"approved":true,"payment_date":"2026-03-24","amount":30},
                  {"approved":false,"payment_date":"2026-03-25","amount":40}
                ]
                """);

        LocalApiServer.DashboardData data = DashboardAnalytics.build(receipts, List.of(project), 3,
                billings, boq, payables, collections, payments, YearMonth.of(2026, 3));

        assertEquals("167.00", data.totalExpenses());
        assertEquals("400.00", data.totalRevenue());
        assertEquals("233.00", data.netProfit());
        assertEquals("280.00", data.outstandingReceivables());
        assertEquals("60.00", data.outstandingPayables());
        assertEquals(3, data.supplierCount());
        assertEquals(2, data.monthly().size());
        assertEquals("300.00", data.monthly().get(0).revenue());
        assertEquals("167.00", data.monthly().get(0).expenses());
        assertEquals("133.00", data.monthly().get(0).profit());
        assertEquals("70.00", data.monthly().get(0).cashFlow());
        assertEquals("100.00", data.monthly().get(1).revenue());
        assertEquals("Official Receipt", data.expenseBreakdown().get(0).name());
        assertEquals("112.00", data.expenseBreakdown().get(0).amount());
        assertEquals("1250.00", data.projectMetrics().get(0).actual());
        assertEquals("125.00", data.projectMetrics().get(0).progress());
        assertTrue(data.cashFlowAvailable());
    }

    private static Receipt receipt(String number, LocalDate date, ReceiptType type, UUID projectId,
                                   String unitPrice, String vat) {
        return new Receipt(UUID.randomUUID(), number, date, type, null, projectId,
                List.of(new ReceiptItem("Recorded item", BigDecimal.ONE, new BigDecimal(unitPrice))),
                BigDecimal.ZERO, new BigDecimal(vat), BigDecimal.ZERO, List.of(), null, null, null);
    }

    private static List<JsonNode> rows(String json) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        JSON.readTree(json).forEach(rows::add);
        return rows;
    }
}
