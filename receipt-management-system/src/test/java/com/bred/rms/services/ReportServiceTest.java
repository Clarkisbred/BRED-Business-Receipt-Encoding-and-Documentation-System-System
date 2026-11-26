package com.bred.rms.services;

import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.services.ReportService.Total;
import com.bred.rms.support.Fakes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {
    Fakes.Receipts receipts;
    Fakes.Suppliers suppliers;
    Fakes.Projects projects;
    ReportService reports;
    Supplier acme;
    Supplier globex;
    Project alpha;

    @BeforeEach
    void setUp() {
        receipts = new Fakes.Receipts();
        suppliers = new Fakes.Suppliers();
        projects = new Fakes.Projects();
        User admin = Fakes.admin();
        var attachments = new Fakes.Attachments();
        var receiptService = new ReceiptService(receipts, suppliers, projects, attachments, () -> admin);
        reports = new ReportService(receiptService, new SupplierService(suppliers, receipts, () -> admin),
                new ProjectService(projects, receipts, () -> admin));
        acme = suppliers.add("Acme", "111");
        globex = suppliers.add("Globex", "222");
        alpha = projects.add("Alpha", "A-1");
    }

    private Receipt add(String no, Supplier s, Project p, String date, String price) {
        return receipts.save(Fakes.receipt(no, s, p, LocalDate.parse(date), "item", price));
    }

    @Test
    void totalsByProjectGroupsUnassigned() {
        add("1", acme, alpha, "2026-11-01", "100.00");
        add("2", acme, alpha, "2026-11-02", "50.50");
        add("3", globex, null, "2026-11-03", "25.00");
        List<Total> totals = reports.totalsByProject(null, null);
        assertEquals(List.of(new Total("Alpha", 2, new BigDecimal("150.50")), new Total("Unassigned", 1, new BigDecimal("25.00"))), totals);
    }

    @Test
    void totalsRespectDateRangeInclusive() {
        add("1", acme, alpha, "2026-10-31", "1.00");
        add("2", acme, alpha, "2026-11-01", "10.00");
        add("3", acme, alpha, "2026-11-30", "100.00");
        add("4", acme, alpha, "2026-12-01", "1000.00");
        List<Total> totals = reports.totalsBySupplier(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"));
        assertEquals(List.of(new Total("Acme", 2, new BigDecimal("110.00"))), totals);
    }

    @Test
    void totalsByMonthAreOldestFirst() {
        add("1", acme, alpha, "2026-11-15", "5.00");
        add("2", globex, alpha, "2026-09-02", "7.00");
        add("3", globex, alpha, "2026-11-01", "1.00");
        assertEquals(List.of(new Total("2026-09", 1, new BigDecimal("7.00")), new Total("2026-11", 2, new BigDecimal("6.00"))),
                reports.totalsByMonth(null, null));
    }

    @Test
    void csvEscapesCommasQuotesAndNewlines() throws IOException {
        Supplier odd = suppliers.add("Smith, \"Sons\"\nTrading", "333");
        Receipt r = add("OR-1", odd, alpha, "2026-11-01", "12.50");
        Path file = Files.createTempFile("rms-report", ".csv");
        file.toFile().deleteOnExit();
        reports.exportCsv(List.of(r), file);
        String csv = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(csv.contains("Receipt No.,Date,Type,Supplier,TIN,Project,Subtotal,Discount,VAT,Additional Charges,Grand Total"), csv);
        assertTrue(csv.contains("\"Smith, \"\"Sons\"\"\nTrading\""), csv);
        assertTrue(csv.contains("OR-1,2026-11-01,Official Receipt,"), csv);
        assertTrue(csv.contains(",Alpha,12.50,0.00,0.00,0.00,12.50"), csv);
    }

    @Test
    void csvPrefixesFormulaCells() throws IOException {
        Supplier evil = suppliers.add("=HYPERLINK(\"http://x\")", "444");
        Receipt r = add("+1", evil, null, "2026-11-01", "1.00");
        Path file = Files.createTempFile("rms-report", ".csv");
        file.toFile().deleteOnExit();
        reports.exportCsv(List.of(r), file);
        String csv = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""), csv);
        assertTrue(csv.contains("'+1,"), csv);
    }

    @Test
    void summarizeCountsThisMonthTotalAndProjectsWithSpend() {
        Project beta = projects.add("Beta", "B-1");
        List<Receipt> all = List.of(add("1", acme, alpha, "2026-11-05", "10.00"), add("2", acme, null, "2026-11-20", "5.00"),
                add("3", acme, beta, "2026-10-30", "100.00"), add("4", acme, alpha, "2025-11-05", "1.00"));
        ReportService.Summary s = ReportService.summarize(all, java.time.YearMonth.of(2026, 11));
        assertEquals(2, s.monthCount());
        assertEquals(new BigDecimal("15.00"), s.monthSpend());
        assertEquals(4, s.totalCount());
        assertEquals(2, s.projectsWithSpend());
        assertEquals(new BigDecimal("116.00"), s.totalSpend());
    }
}
