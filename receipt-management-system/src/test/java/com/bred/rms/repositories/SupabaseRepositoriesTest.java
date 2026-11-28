package com.bred.rms.repositories;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bred.rms.models.ActivityEntry;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.supabase.SupabaseActivityLogRepository;
import com.bred.rms.repositories.supabase.SupabaseMessageRepository;
import com.bred.rms.repositories.supabase.SupabaseProjectRepository;
import com.bred.rms.repositories.supabase.SupabaseReceiptRepository;
import com.bred.rms.repositories.supabase.SupabaseSupplierRepository;
import com.bred.rms.repositories.supabase.SupabaseUserRepository;
import com.bred.rms.support.TestEnv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupabaseRepositoriesTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String RID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String SID = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PID = "cccccccc-0000-0000-0000-000000000001";
    private static final String CID = "eeeeeeee-0000-0000-0000-000000000001";

    private static final String RECEIPT_JSON = """
            [{"id":"%s","receipt_no":"OR-100","receipt_date":"2026-11-05","receipt_type":"sales_invoice",
              "supplier_id":"%s","project_id":"%s","company_id":"%s","discount":11.00,"vat":28.80,"additional_charges":5,
              "classification":"Office supplies",
              "encoded_by":"dddddddd-0000-0000-0000-000000000001",
              "created_at":"2026-11-05T01:02:03.123456+00:00","modified_at":"2026-11-06T01:02:03+00:00",
              "receipt_items":[{"line_no":2,"description":"Hammer","quantity":1,"unit_price":50},
                               {"line_no":1,"description":"Nails","quantity":2.000,"unit_price":100.50}],
              "receipt_attachments":[{"file_name":"scan.pdf","storage_path":"%s/1-scan.pdf"}]}]
            """.formatted(RID, SID, PID, CID, RID);

    @Test
    void receiptRepositoryMapsRowsWithItemsAndAttachments() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/receipts", 200, RECEIPT_JSON);
            List<Receipt> all = new SupabaseReceiptRepository(env.rest).findAll();
            assertEquals(1, all.size());
            Receipt r = all.get(0);
            assertEquals("OR-100", r.receiptNo());
            assertEquals(ReceiptType.SALES_INVOICE, r.type());
            assertEquals(LocalDate.of(2026, 11, 5), r.date());
            assertEquals(UUID.fromString(CID), r.companyId());
            assertEquals("Nails", r.items().get(0).description());
            assertEquals("Hammer", r.items().get(1).description());
            assertEquals(new BigDecimal("273.80"), r.grandTotal());
            assertEquals("OFFICE SUPPLIES", r.classification());
            assertEquals("scan.pdf", r.attachments().get(0).fileName());
            assertEquals(Instant.parse("2026-11-05T01:02:03.123456Z"), r.createdAt());
            String q = env.fake.requests("GET", "/rest/v1/receipts").get(0).pathAndQuery();
            assertTrue(q.contains("receipt_items") && q.contains("receipt_attachments"), "query embeds children: " + q);
        }
    }

    private static Receipt sample() {
        return new Receipt(UUID.fromString(RID), " OR-100 ", LocalDate.of(2026, 11, 5), ReceiptType.SALES_INVOICE,
                UUID.fromString(SID), null,
                List.of(new ReceiptItem("Nails", new BigDecimal("2"), new BigDecimal("100.50")),
                        new ReceiptItem("Hammer", BigDecimal.ONE, new BigDecimal("50"))),
                new BigDecimal("11.00"), new BigDecimal("28.80"), new BigDecimal("5.00"),
                List.of(new Attachment("scan.pdf", RID + "/1-scan.pdf")), null, null, null,
                null, "", "Cash", BigDecimal.ZERO, "Office supplies");
    }

    @Test
    void saveCallsSaveReceiptRpcWithItemsAndReturnsStoredReceipt() throws Exception {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/save_receipt", 200, "\"" + RID + "\"");
            env.fake.on("GET", "/rest/v1/receipts", 200, RECEIPT_JSON);
            Receipt saved = new SupabaseReceiptRepository(env.rest).save(sample());
            assertEquals(UUID.fromString(RID), saved.id());
            JsonNode p = M.readTree(env.fake.requests("POST", "/rest/v1/rpc/save_receipt").get(0).bodyString()).get("p");
            assertEquals("OR-100", p.get("receipt_no").asText());
            assertEquals("2026-11-05", p.get("receipt_date").asText());
            assertEquals("sales_invoice", p.get("receipt_type").asText());
            assertEquals(SID, p.get("supplier_id").asText());
            assertTrue(p.get("project_id").isNull());
            assertEquals("OFFICE SUPPLIES", p.get("classification").asText());
            assertEquals(2, p.get("items").size());
            assertTrue(p.get("items").get(0).get("unit_price").isNumber(), "unit_price is sent as a JSON number");
            assertEquals(0, new BigDecimal("100.50").compareTo(p.get("items").get(0).get("unit_price").decimalValue()));
            assertEquals(RID + "/1-scan.pdf", p.get("attachments").get(0).get("storage_path").asText());
        }
    }

    @Test
    void approvedImportOmitsCompanyAndAttachmentMetadataFromInitialWrite() throws Exception {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/approve_ai_import_receipt", 200, "\"" + RID + "\"");
            env.fake.on("GET", "/rest/v1/receipts", 200, RECEIPT_JSON);
            Receipt ordinary = sample();
            Receipt imported = new Receipt(null, ordinary.receiptNo(), ordinary.date(), ordinary.type(),
                    ordinary.supplierId(), ordinary.projectId(), ordinary.items(), ordinary.discount(),
                    ordinary.vat(), ordinary.additionalCharges(), ordinary.attachments(), null, null, null);

            new SupabaseReceiptRepository(env.rest).saveApprovedImport(imported);

            JsonNode p = M.readTree(env.fake.requests("POST", "/rest/v1/rpc/approve_ai_import_receipt")
                    .get(0).bodyString()).get("p");
            assertFalse(p.has("company_id"));
            assertEquals(0, p.get("attachments").size());
        }
    }

    @Test
    void approvedAttachmentLinkingUsesAdministratorGate() throws Exception {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/approve_ai_import_receipt", 200, "\"" + RID + "\"");
            env.fake.on("GET", "/rest/v1/receipts", 200, RECEIPT_JSON);

            new SupabaseReceiptRepository(env.rest).saveApprovedImportAttachments(sample());

            JsonNode p = M.readTree(env.fake.requests("POST", "/rest/v1/rpc/approve_ai_import_receipt")
                    .get(0).bodyString()).get("p");
            assertEquals(RID, p.get("id").asText());
            assertEquals("attachments", p.get("operation").asText());
            assertEquals(RID + "/1-scan.pdf", p.get("attachments").get(0).get("storage_path").asText());
        }
    }

    @Test
    void approvedImportReportsCommittedIdWhenReadbackIsUnavailable() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/approve_ai_import_receipt", 200, "\"" + RID + "\"");
            env.fake.on("GET", "/rest/v1/receipts", 200, "[]");

            var error = assertThrows(com.bred.rms.repositories.SavedReceiptReadbackException.class,
                    () -> new SupabaseReceiptRepository(env.rest).saveApprovedImport(
                            new Receipt(null, sample().receiptNo(), sample().date(), sample().type(),
                                    sample().supplierId(), null, sample().items(), sample().discount(), sample().vat(),
                                    sample().additionalCharges(), List.of(), null, null, null)));

            assertEquals(UUID.fromString(RID), error.receiptId());
        }
    }

    @Test
    void rpcErrorMessageSurfacesAsValidationException() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/save_receipt", 400,
                    "{\"code\":\"P0001\",\"message\":\"Duplicate receipt: OR-100 already exists for this supplier.\",\"details\":null}");
            ValidationException e = assertThrows(ValidationException.class, () -> new SupabaseReceiptRepository(env.rest).save(sample()));
            assertTrue(e.getMessage().contains("Duplicate receipt"));
        }
    }

    @Test
    void deleteReturnsStoragePathsFromRpc() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/delete_receipt", 200, "[\"" + RID + "/1-scan.pdf\"]");
            List<String> paths = new SupabaseReceiptRepository(env.rest).delete(UUID.fromString(RID));
            assertEquals(List.of(RID + "/1-scan.pdf"), paths);
        }
    }

    @Test
    void unauthorizedRefreshesTokenAndRetriesOnce() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/suppliers", 401, "{\"message\":\"JWT expired\"}");
            env.fake.on("GET", "/rest/v1/suppliers", 200, "[]");
            env.fake.on("POST", "/auth/v1/token", 200, TestEnv.refreshResponse());
            assertEquals(0, new SupabaseSupplierRepository(env.rest).findAll().size());
            assertEquals(1, env.fake.requests("POST", "/auth/v1/token").size());
            var gets = env.fake.requests("GET", "/rest/v1/suppliers");
            assertEquals(2, gets.size());
            assertEquals("Bearer tok2", gets.get(1).headers().get("authorization"));
        }
    }

    @Test
    void unauthorizedTwiceGivesSessionExpiredMessage() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/suppliers", 401, "{\"message\":\"JWT expired\"}");
            env.fake.on("POST", "/auth/v1/token", 200, TestEnv.refreshResponse());
            RepositoryException e = assertThrows(RepositoryException.class, () -> new SupabaseSupplierRepository(env.rest).findAll());
            assertTrue(e.getMessage().contains("sign in again"), e.getMessage());
            assertEquals(2, env.fake.requests("GET", "/rest/v1/suppliers").size());
            assertEquals(1, env.fake.requests("POST", "/auth/v1/token").size());
        }
    }

    @Test
    void networkDownGivesFriendlyMessage() {
        TestEnv env = new TestEnv();
        env.close();
        RepositoryException e = assertThrows(RepositoryException.class, () -> new SupabaseSupplierRepository(env.rest).findAll());
        assertTrue(e.getMessage().contains("internet connection"), e.getMessage());
        assertTrue(e.isNetwork());
    }

    @Test
    void malformedJsonGivesFriendlyMessage() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/suppliers", 200, "<html>oops</html>");
            RepositoryException e = assertThrows(RepositoryException.class, () -> new SupabaseSupplierRepository(env.rest).findAll());
            assertTrue(e.getMessage().contains("unexpected response"), e.getMessage());
        }
    }

    @Test
    void permissionDeniedGivesFriendlyMessage() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/activity_log", 403, "{\"message\":\"permission denied for table activity_log\"}");
            RepositoryException e = assertThrows(RepositoryException.class, () -> new SupabaseActivityLogRepository(env.rest).findAll());
            assertTrue(e.getMessage().contains("permission"), e.getMessage());
        }
    }

    @Test
    void supplierSaveInsertsWhenIdIsNullAndPatchesWhenPresent() throws Exception {
        try (TestEnv env = new TestEnv()) {
            String row = "[{\"id\":\"" + SID + "\",\"business_name\":\"Acme\",\"tin\":\"123\",\"address\":\"A\",\"contact_number\":\"9\"}]";
            env.fake.on("POST", "/rest/v1/suppliers", 201, row);
            env.fake.on("PATCH", "/rest/v1/suppliers", 200, row);
            var repo = new SupabaseSupplierRepository(env.rest);
            Supplier created = repo.save(new Supplier(null, " Acme ", " 123 ", "A", "9"));
            assertEquals(UUID.fromString(SID), created.id());
            JsonNode body = M.readTree(env.fake.requests("POST", "/rest/v1/suppliers").get(0).bodyString());
            assertTrue(!body.has("id"), "new supplier is sent without an id");
            assertEquals("Acme", body.get("business_name").asText());
            assertEquals("123", body.get("tin").asText());
            repo.save(created);
            assertEquals("/rest/v1/suppliers?id=eq." + SID, env.fake.requests("PATCH", "/rest/v1/suppliers").get(0).pathAndQuery());
        }
    }

    @Test
    void supplierDeleteInUseGivesFriendlyMessage() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("DELETE", "/rest/v1/suppliers", 409,
                    "{\"code\":\"23503\",\"message\":\"update or delete on table \\\"suppliers\\\" violates foreign key constraint \\\"receipts_supplier_id_fkey\\\" on table \\\"receipts\\\"\"}");
            ValidationException e = assertThrows(ValidationException.class, () -> new SupabaseSupplierRepository(env.rest).delete(UUID.fromString(SID)));
            assertTrue(e.getMessage().contains("still used"), e.getMessage());
        }
    }

    @Test
    void projectRepositoryMapsRows() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/projects", 200,
                    "[{\"id\":\"" + PID + "\",\"name\":\"Bridge\",\"code\":\"BR-01\",\"fund_source\":\"General Fund\"}]");
            Project p = new SupabaseProjectRepository(env.rest).findAll().get(0);
            assertEquals("BR-01", p.code());
            assertEquals("General Fund", p.fundSource());
        }
    }

    @Test
    void userCreateCallsEdgeFunction() throws Exception {
        try (TestEnv env = new TestEnv()) {
            String id = "eeeeeeee-0000-0000-0000-000000000001";
            env.fake.on("POST", "/functions/v1/admin-create-user", 200, "{\"id\":\"" + id + "\"}");
            User u = new SupabaseUserRepository(env.rest).create("eli@x.test", "Eli", Role.ENCODER, "secret1".toCharArray());
            assertEquals(UUID.fromString(id), u.id());
            assertEquals(Role.ENCODER, u.role());
            JsonNode b = M.readTree(env.fake.requests("POST", "/functions/v1/admin-create-user").get(0).bodyString());
            assertEquals("eli@x.test", b.get("email").asText());
            assertEquals("Eli", b.get("full_name").asText());
            assertEquals("encoder", b.get("role").asText());
            assertEquals("secret1", b.get("password").asText());
        }
    }

    @Test
    void publicRegistrationUsesRegistrationFunctionWithoutSigningIn() throws Exception {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/functions/v1/register-account", 201, "{\"message\":\"submitted\"}");
            UUID companyId = UUID.fromString("11111111-0000-0000-0000-000000000001");
            new SupabaseUserRepository(env.rest).registerAccount("Ada", "ada@x.test", Role.ENCODER,
                    companyId, null, "Password123".toCharArray());
            assertEquals(1, env.fake.requests("POST", "/functions/v1/register-account").size());
            JsonNode body = M.readTree(env.fake.requests("POST", "/functions/v1/register-account").get(0).bodyString());
            assertEquals("Password123", body.get("password").asText());
            assertEquals(companyId.toString(), body.get("company_id").asText());
            assertTrue(env.sessions.current().isPresent(), "registration must not replace the current session");
        }
    }

    @Test
    void adminRegistrationSendsCompanyCreationMode() throws Exception {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/functions/v1/register-account", 201, "{\"message\":\"ready\"}");
            new SupabaseUserRepository(env.rest).registerAccount("Ada", "ada@x.test", Role.ADMIN,
                    null, "Acme Ltd", "Password123".toCharArray());
            JsonNode body = M.readTree(env.fake.requests("POST", "/functions/v1/register-account").get(0).bodyString());
            assertEquals("admin", body.get("role").asText());
            assertEquals("Acme Ltd", body.get("company_name").asText());
            assertFalse(body.has("company_id"));
        }
    }

    @Test
    void outdatedRegistrationFunctionShowsProviderNeutralRecovery() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/functions/v1/register-account", 400,
                    "{\"error\":\"Choose an available company.\"}");
            RepositoryException error = assertThrows(RepositoryException.class,
                    () -> new SupabaseUserRepository(env.rest).registerAccount("Ada", "ada@x.test", Role.ADMIN,
                            null, "Acme Ltd", "Password123".toCharArray()));
            assertTrue(error.getMessage().contains("Restart BRED and try again"));
            assertTrue(error.getMessage().contains("contact your administrator"));
            assertFalse(error.getMessage().contains("Supabase"));
        }
    }

    @Test
    void availableCompaniesComeFromPublicRegistrationFunction() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/functions/v1/register-account", 200,
                    "[{\"id\":\"11111111-0000-0000-0000-000000000001\",\"name\":\"Acme Ltd\"}]");
            var companies = new SupabaseUserRepository(env.rest).availableCompanies();
            assertEquals(1, companies.size());
            assertEquals("Acme Ltd", companies.get(0).name());
            assertTrue(env.fake.requests("GET", "/functions/v1/register-account").get(0)
                    .pathAndQuery().contains("mode=companies"));
        }
    }

    @Test
    void missingRegistrationFunctionShowsProviderNeutralRecovery() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/functions/v1/register-account", 404,
                    "{\"message\":\"Function not found\"}");
            RepositoryException error = assertThrows(RepositoryException.class,
                    () -> new SupabaseUserRepository(env.rest).availableCompanies());
            assertTrue(error.getMessage().contains("account service is unavailable"));
            assertTrue(error.getMessage().contains("Contact your administrator"));
            assertFalse(error.getMessage().contains("Supabase"));
        }
    }

    @Test
    void undeployedPasswordResetFunctionShowsProviderNeutralRecovery() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/functions/v1/admin-send-password-reset", 404,
                    "{\"code\":\"NOT_FOUND\",\"message\":\"Requested function was not found\"}");
            RepositoryException error = assertThrows(RepositoryException.class,
                    () -> new SupabaseUserRepository(env.rest).sendPasswordReset(UUID.fromString(SID)));
            assertTrue(error.getMessage().contains("account service is unavailable"));
            assertFalse(error.getMessage().contains("Supabase"));
        }
    }

    @Test
    void passwordResetForMissingAccountKeepsTheServerMessage() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/functions/v1/admin-send-password-reset", 404,
                    "{\"error\":\"That account no longer exists.\"}");
            var error = assertThrows(com.bred.rms.models.ValidationException.class,
                    () -> new SupabaseUserRepository(env.rest).sendPasswordReset(UUID.fromString(SID)));
            assertTrue(error.getMessage().contains("no longer exists"));
        }
    }

    @Test
    void registrationReviewUsesProtectedFunction() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/functions/v1/review-account", 200, "{\"account_status\":\"APPROVED\"}");
            new SupabaseUserRepository(env.rest).reviewRegistration(UUID.fromString(SID),
                    com.bred.rms.models.AccountStatus.APPROVED);
            assertTrue(env.fake.requests("POST", "/functions/v1/review-account").get(0).bodyString()
                    .contains("\"action\":\"APPROVE\""));
        }
    }

    @Test
    void userRepositoryMapsProfilesAndUnknownRoleFallsBackToEncoder() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/profiles", 200,
                    "[{\"id\":\"" + SID + "\",\"full_name\":\"Ada\",\"email\":\"a@x.test\",\"role\":\"admin\",\"is_active\":true,"
                            + "\"companies\":{\"name\":\"Acme Ltd\"}},"
                            + "{\"id\":\"" + PID + "\",\"full_name\":\"Odd\",\"email\":\"o@x.test\",\"role\":\"wizard\",\"is_active\":false}]");
            List<User> users = new SupabaseUserRepository(env.rest).findAll();
            assertEquals(Role.ADMIN, users.get(0).role());
            assertEquals("Acme Ltd", users.get(0).companyName());
            assertEquals(Role.ENCODER, users.get(1).role());
            assertEquals(false, users.get(1).active());
        }
    }

    @Test
    void updateProfileCallsRpc() throws Exception {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("POST", "/rest/v1/rpc/admin_update_profile", 200, "null");
            new SupabaseUserRepository(env.rest).update(UUID.fromString(SID), "Ada L.", Role.ADMIN, true);
            JsonNode b = M.readTree(env.fake.requests("POST", "/rest/v1/rpc/admin_update_profile").get(0).bodyString());
            assertEquals(SID, b.get("p_id").asText());
            assertEquals("admin", b.get("p_role").asText());
            assertEquals(true, b.get("p_active").asBoolean());
        }
    }

    @Test
    void activityLogMapsRowsAndToleratesMissingUserName() {
        try (TestEnv env = new TestEnv()) {
            env.fake.on("GET", "/rest/v1/activity_log", 200,
                    "[{\"at\":\"2026-11-05T01:02:03+00:00\",\"user_id\":null,\"user_name\":null,\"action\":\"receipt.create\",\"detail\":\"OR-1\"}]");
            ActivityEntry e = new SupabaseActivityLogRepository(env.rest).findAll().get(0);
            assertEquals("receipt.create", e.action());
            assertEquals("", e.userName());
            assertEquals(null, e.userId());
        }
    }

    @Test
    void companyChatReadsCompanyMessagesAndMapsUrgentFlag() {
        try (TestEnv env = new TestEnv()) {
            UUID companyId = UUID.fromString("11111111-0000-0000-0000-000000000001");
            env.fake.on("GET", "/rest/v1/custom_messages", 200,
                    "[{\"id\":\"" + RID + "\",\"sender_id\":\"" + SID + "\",\"recipient_id\":null,"
                            + "\"company_id\":\"" + companyId + "\",\"sender_name\":\"Ada\","
                            + "\"sender_email\":\"a@x.test\",\"recipient_name\":\"\",\"body\":\"Meeting now\","
                            + "\"sent_at\":\"2026-11-05T01:02:03Z\",\"is_broadcast\":false,\"is_urgent\":true}]");

            var messages = new SupabaseMessageRepository(env.rest).findCompanyChat(companyId);
            assertEquals(1, messages.size());
            assertEquals(companyId, messages.get(0).companyId());
            assertTrue(messages.get(0).urgent());
            String query = env.fake.requests("GET", "/rest/v1/custom_messages").get(0).pathAndQuery();
            assertTrue(query.contains("company_id=eq." + companyId));
            assertTrue(query.contains("recipient_id=is.null"));
        }
    }

    @Test
    void companyChatSendIncludesUrgencyAndCompanyScope() throws Exception {
        try (TestEnv env = new TestEnv()) {
            UUID companyId = UUID.fromString("11111111-0000-0000-0000-000000000001");
            env.fake.on("POST", "/rest/v1/custom_messages", 201,
                    "[{\"id\":\"" + RID + "\",\"sender_id\":\"" + SID + "\",\"recipient_id\":null,"
                            + "\"company_id\":\"" + companyId + "\",\"sender_name\":\"Ada\","
                            + "\"sender_email\":\"a@x.test\",\"recipient_name\":\"\",\"body\":\"Meeting now\","
                            + "\"sent_at\":\"2026-11-05T01:02:03Z\",\"is_broadcast\":false,\"is_urgent\":true}]");

            var sent = new SupabaseMessageRepository(env.rest).sendCompanyChat(companyId, " Meeting now ", true);
            assertTrue(sent.urgent());
            JsonNode body = M.readTree(env.fake.requests("POST", "/rest/v1/custom_messages").get(0).bodyString());
            assertTrue(body.get("recipient_id").isNull());
            assertEquals(companyId.toString(), body.get("company_id").asText());
            assertEquals("Meeting now", body.get("body").asText());
            assertTrue(body.get("is_urgent").asBoolean());
        }
    }
}
