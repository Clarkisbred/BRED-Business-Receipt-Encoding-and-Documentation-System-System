package com.bred.rms.repositories.supabase;

import com.bred.rms.support.TestEnv;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupabaseLocalDataImporterTest {
    private static final String RECEIPT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String SUPPLIER_ID = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PROJECT_ID = "cccccccc-0000-0000-0000-000000000001";
    private static final String LOCAL_USER_ID = "dddddddd-0000-0000-0000-000000000001";
    private static final String CLOUD_USER_ID = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String ATTACHMENT_ID = "ffffffff-0000-0000-0000-000000000001";

    @TempDir
    Path tempDirectory;

    @Test
    void importsLocalRecordsAndCanBeRetried() throws Exception {
        String storagePath = RECEIPT_ID + "/" + ATTACHMENT_ID + "-scan.pdf";
        Path attachmentPath = tempDirectory.resolve("attachments").resolve(storagePath);
        Files.createDirectories(attachmentPath.getParent());
        Files.writeString(attachmentPath, "pdf-bytes");
        Files.writeString(tempDirectory.resolve("data.json"), """
                {
                  "version": 1,
                  "users": [{"id":"%s","email":"local@example.com"}],
                  "suppliers": [{"id":"%s","businessName":"Acme","tin":"123","address":"A","contactNumber":"9"}],
                  "projects": [{"id":"%s","name":"Bridge","code":"BR-1","fundSource":"General"}],
                  "receipts": [{
                    "id":"%s","receiptNo":"R-1","date":"2025-01-02","type":"official_receipt",
                    "supplierId":"%s","projectId":"%s","items":[{"description":"Part","quantity":"1","unit":"kg","unitPrice":"5"}],
                    "discount":"0","vat":"0","additionalCharges":"0",
                    "notes":"Tooling for repairs","paymentMethod":"Card","ewtRate":"2.5",
                    "classification":"Maintenance",
                    "attachments":[{"fileName":"scan.pdf","storagePath":"%s"}],
                    "encodedBy":"%s","createdAt":"2025-01-02T00:00:00Z","modifiedAt":"2025-01-02T00:00:00Z"
                  }],
                  "activity": [{
                    "at":"2025-01-02T00:00:00Z","userId":"%s","userName":"Local User",
                    "action":"receipt.create","detail":"R-1"
                  }]
                }
                """.formatted(LOCAL_USER_ID, SUPPLIER_ID, PROJECT_ID, RECEIPT_ID, SUPPLIER_ID, PROJECT_ID,
                storagePath, LOCAL_USER_ID, LOCAL_USER_ID));

        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/profiles", 200,
                    "[{\"id\":\"" + CLOUD_USER_ID + "\",\"email\":\"LOCAL@example.com\"}]");
            env.fake.on("POST", "/rest/v1/suppliers", 201, "[]");
            env.fake.on("POST", "/rest/v1/projects", 201, "[]");
            env.fake.on("POST", "/storage/v1/object/receipts/", 200, "{}");
            env.fake.on("POST", "/rest/v1/rpc/save_receipt", 200, "\"" + RECEIPT_ID + "\"");
            env.fake.on("GET", "/rest/v1/receipts", 200, """
                    [{"id":"%s","receipt_no":"R-1","receipt_date":"2025-01-02","receipt_type":"official_receipt",
                      "supplier_id":"%s","project_id":"%s","receipt_items":[],
                      "receipt_attachments":[{"file_name":"scan.pdf","storage_path":"%s"}]}]
                    """.formatted(RECEIPT_ID, SUPPLIER_ID, PROJECT_ID, storagePath));
            env.fake.on("POST", "/functions/v1/admin-import-activity", 200, "{\"processed\":1}");

            var importer = new SupabaseLocalDataImporter(env.rest, new SupabaseReceiptRepository(env.rest),
                    new SupabaseAttachmentStore(env.rest));
            var first = importer.importDirectory(tempDirectory);
            var second = importer.importDirectory(tempDirectory);

            assertEquals(1, first.suppliers());
            assertEquals(1, first.projects());
            assertEquals(1, first.receipts());
            assertEquals(1, first.attachments());
            assertEquals(1, first.activityEntries());
            assertEquals(1, first.localUsers());
            assertEquals(first, second);

            var upsert = env.fake.requests("POST", "/rest/v1/suppliers").get(0);
            assertTrue(upsert.pathAndQuery().contains("on_conflict=id"));
            var upload = env.fake.requests("POST", "/storage/v1/object/receipts/").get(0);
            assertEquals("true", upload.headers().get("x-upsert"));
            var receiptPayload = env.fake.requests("POST", "/rest/v1/rpc/save_receipt").get(0).bodyString();
            assertTrue(receiptPayload.contains("\"unit\":\"kg\""));
            assertTrue(receiptPayload.contains("\"notes\":\"Tooling for repairs\""));
            assertTrue(receiptPayload.contains("\"payment_method\":\"Card\""));
            assertTrue(receiptPayload.contains("\"classification\":\"MAINTENANCE\""));
            assertTrue(receiptPayload.contains("\"ewt_rate\":2.5"));
            var activity = env.fake.requests("POST", "/functions/v1/admin-import-activity").get(0).bodyString();
            assertTrue(activity.contains(CLOUD_USER_ID), "local activity is linked to the matching cloud account");
        }
    }

    @Test
    void importsExcelReceiptsAndSkipsThemOnRetry() throws Exception {
        String receiptId = "11111111-0000-0000-0000-000000000001";
        String supplierId = "22222222-0000-0000-0000-000000000001";
        String projectId = "33333333-0000-0000-0000-000000000001";
        Path workbookPath = tempDirectory.resolve("receipts.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Receipts");
            Row headers = sheet.createRow(0);
            String[] columns = {"receipt_no", "date", "type", "supplier_name", "supplier_tin",
                    "supplier_address", "supplier_contact", "project_name", "project_code", "project_fund_source",
                    "item_description", "quantity", "unit_price", "discount", "vat", "additional_charges"};
            for (int i = 0; i < columns.length; i++) headers.createCell(i).setCellValue(columns[i]);
            addExcelRow(sheet.createRow(1), "R-EXCEL", "Printer paper");
            addExcelRow(sheet.createRow(2), "R-EXCEL", "Pens");
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int i = 1; i <= 2; i++) {
                var date = sheet.getRow(i).getCell(1);
                date.setCellValue(org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                        LocalDateTime.of(2025, 2, 3, 0, 0)));
                date.setCellStyle(dateStyle);
            }
            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/suppliers", 200, "[]")
                    .on("GET", "/rest/v1/suppliers", 200, """
                            [{"id":"%s","business_name":"Acme","tin":"123","address":"Main St","contact_number":"555"}]
                            """.formatted(supplierId))
                    .on("POST", "/rest/v1/suppliers", 201, """
                            [{"id":"%s","business_name":"Acme","tin":"123","address":"Main St","contact_number":"555"}]
                            """.formatted(supplierId));
            env.fake.on("GET", "/rest/v1/projects", 200, "[]")
                    .on("GET", "/rest/v1/projects", 200, """
                            [{"id":"%s","name":"Bridge","code":"BR-1","fund_source":"General"}]
                            """.formatted(projectId))
                    .on("POST", "/rest/v1/projects", 201, """
                            [{"id":"%s","name":"Bridge","code":"BR-1","fund_source":"General"}]
                            """.formatted(projectId));
            String receipt = """
                    [{"id":"%s","receipt_no":"R-EXCEL","receipt_date":"2025-02-03","receipt_type":"official_receipt",
                      "supplier_id":"%s","project_id":"%s","receipt_items":[],"receipt_attachments":[]}]
                    """.formatted(receiptId, supplierId, projectId);
            env.fake.on("GET", "/rest/v1/receipts", 200, "[]")
                    .on("GET", "/rest/v1/receipts", 200, receipt)
                    .on("POST", "/rest/v1/rpc/save_receipt", 200, "\"" + receiptId + "\"");

            var importer = new SupabaseLocalDataImporter(env.rest, new SupabaseReceiptRepository(env.rest),
                    new SupabaseAttachmentStore(env.rest));
            var first = importer.importExcel(workbookPath);
            var retry = importer.importExcel(workbookPath);

            assertEquals(1, first.suppliersCreated());
            assertEquals(1, first.projectsCreated());
            assertEquals(1, first.receiptsImported());
            assertEquals(0, first.receiptsSkipped());
            assertEquals(0, retry.suppliersCreated());
            assertEquals(0, retry.projectsCreated());
            assertEquals(0, retry.receiptsImported());
            assertEquals(1, retry.receiptsSkipped());

            var savedReceipt = env.fake.requests("POST", "/rest/v1/rpc/save_receipt").get(0).bodyString();
            assertTrue(savedReceipt.contains("Printer paper"));
            assertTrue(savedReceipt.contains("Pens"), "multiple spreadsheet lines become one receipt with multiple items");
        }
    }

    private static void addExcelRow(Row row, String receiptNo, String item) {
        row.createCell(0).setCellValue(receiptNo);
        row.createCell(1).setCellValue("2025-02-03");
        row.createCell(2).setCellValue("Official Receipt");
        row.createCell(3).setCellValue("Acme");
        row.createCell(4).setCellValue("123");
        row.createCell(5).setCellValue("Main St");
        row.createCell(6).setCellValue("555");
        row.createCell(7).setCellValue("Bridge");
        row.createCell(8).setCellValue("BR-1");
        row.createCell(9).setCellValue("General");
        row.createCell(10).setCellValue(item);
        row.createCell(11).setCellValue(2);
        row.createCell(12).setCellValue(10);
        row.createCell(13).setCellValue(0);
        row.createCell(14).setCellValue(0);
        row.createCell(15).setCellValue(0);
    }
}
