package com.bred.rms.services;

import com.bred.rms.support.TestEnv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionDataServiceTest {
    @Test
    void billingAnalyticsGroupsLiveTotalsByProjectAndKeepsReceivablesNonNegative() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/project_billings", 200, """
                    [{"project_id":"22222222-0000-0000-0000-000000000001",
                      "gross_amount":100,"net_amount":90,"collected_amount":40,"status":"Overdue"},
                     {"project_id":"22222222-0000-0000-0000-000000000001",
                      "gross_amount":50,"net_amount":50,"collected_amount":20,"status":"Certified"},
                     {"project_id":"22222222-0000-0000-0000-000000000002",
                      "gross_amount":80,"net_amount":80,"collected_amount":100,"status":"Certified"}]
                    """);

            var analytics = new ConstructionDataService(env.rest).billingAnalytics();

            assertEquals(0, new BigDecimal("230").compareTo(analytics.summary().grossBillings()));
            assertEquals(0, new BigDecimal("160").compareTo(analytics.summary().collected()));
            assertEquals(0, new BigDecimal("80").compareTo(analytics.summary().receivable()));
            assertEquals(1, analytics.summary().overdueBillings());
            assertEquals(2, analytics.byProject().size());
            assertEquals(0, new BigDecimal("150").compareTo(analytics.byProject().get(0).grossBillings()));
            assertEquals(0, new BigDecimal("60").compareTo(analytics.byProject().get(0).collected()));
            assertEquals(0, new BigDecimal("80").compareTo(analytics.byProject().get(0).receivable()));
            assertEquals(0, BigDecimal.ZERO.compareTo(analytics.byProject().get(1).receivable()));
        }
    }

    @Test
    void boqCostSummaryAndReportUseAuthorizedAggregateData() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/project_boq_cost_variance", 200, """
                    [{"project_id":"22222222-0000-0000-0000-000000000001",
                      "estimated_total":100000.00,"actual_total":112500.00,
                      "variance_peso":12500.00,"variance_percent":12.5000,"status":"Over budget"},
                     {"project_id":"22222222-0000-0000-0000-000000000002",
                      "estimated_total":80000.00,"actual_total":75000.00,
                      "variance_peso":-5000.00,"variance_percent":-6.2500,"status":"Under budget"}]
                    """);
            env.fake.on("GET", "/rest/v1/projects", 200, """
                    [{"id":"22222222-0000-0000-0000-000000000001","name":"Bridge Alpha"},
                     {"id":"22222222-0000-0000-0000-000000000002","name":"Bridge Beta"}]
                    """);

            ConstructionDataService service = new ConstructionDataService(env.rest);
            var analytics = service.boqCostAnalytics();
            ConstructionDataService.BOQCostSummary summary = analytics.summary();

            assertEquals(new BigDecimal("180000.00"), summary.estimated());
            assertEquals(new BigDecimal("187500.00"), summary.actual());
            assertEquals(new BigDecimal("7500.00"), summary.variance());
            assertEquals("Over budget", summary.status());

            var report = service.constructionReport("BOQ cost variance");
            assertEquals(2, report.size());
            assertEquals("Bridge Alpha", report.get(0).label());
            assertEquals(0, new BigDecimal("12500.00").compareTo(report.get(0).amount()));
            assertEquals("Over budget", report.get(0).status());
            assertEquals("Bridge Beta", report.get(1).label());
            assertEquals(0, new BigDecimal("-5000.00").compareTo(report.get(1).amount()));
            assertEquals(2, analytics.byProject().size());
            assertEquals(0, new BigDecimal("12500.00").compareTo(analytics.byProject().get(0).variance()));
            assertEquals(0, new BigDecimal("-5000.00").compareTo(analytics.byProject().get(1).variance()));
        }
    }

    @Test
    void boqCostSummaryFallsBackToRealBoqRows() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/project_boq_cost_variance", 404, """
                    {"message":"relation unavailable"}
                    """);
            env.fake.on("GET", "/rest/v1/project_boq_items", 200, """
                    [{"project_id":"22222222-0000-0000-0000-000000000001",
                      "estimated_cost":50000,"actual_cost":45000},
                     {"project_id":"22222222-0000-0000-0000-000000000001",
                      "estimated_cost":25000,"actual_cost":30000}]
                    """);

            ConstructionDataService service = new ConstructionDataService(env.rest);
            var analytics = service.boqCostAnalytics();
            ConstructionDataService.BOQCostSummary summary = analytics.summary();

            assertEquals(new BigDecimal("75000.00"), summary.estimated());
            assertEquals(new BigDecimal("75000.00"), summary.actual());
            assertEquals(BigDecimal.ZERO.setScale(2), summary.variance());
            assertEquals("On budget", summary.status());
            assertEquals(1, analytics.byProject().size());
            assertEquals(0, new BigDecimal("0.00").compareTo(analytics.byProject().get(0).variance()));
        }
    }

    @Test
    void taxCalendarReportAddsQuarterAndDueState() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/tax_calendar_items", 200, """
                    [{"name":"Expanded Withholding Tax","frequency":"Monthly","due_date":"2026-12-15","status":"Pending"},
                     {"name":"Quarterly Income Tax","frequency":"Quarterly","due_date":"2026-10-15","status":"Pending"}]
                    """);

            var report = new ConstructionDataService(env.rest).constructionReport("Tax calendar");
            assertEquals(2, report.size());
            assertEquals("Expanded Withholding Tax · 2026-Q4 · Monthly", report.get(0).label());
            assertEquals("Due soon", report.get(0).status());
            assertEquals("Quarterly Income Tax · 2026-Q4 · Quarterly", report.get(1).label());
            assertEquals("Overdue", report.get(1).status());
        }
    }

    @Test
    void billingAgingReportUsesBucketsAndOutstandingAmounts() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/project_billing_aging", 200, """
                    [{"billing_number":"BILL-001","submitted_date":"2026-07-01","due_date":"2026-07-15",
                      "status":"Certified","net_amount":100000,"collected_amount":25000,"outstanding_amount":75000}]
                    """);

            var report = new ConstructionDataService(env.rest).constructionReport("Billing aging");
            assertEquals(1, report.size());
            assertEquals("Over 90", report.get(0).status());
            assertEquals(0, new BigDecimal("75000").compareTo(report.get(0).amount()));
            assertEquals(true, report.get(0).label().contains("days overdue"));
        }
    }
}
