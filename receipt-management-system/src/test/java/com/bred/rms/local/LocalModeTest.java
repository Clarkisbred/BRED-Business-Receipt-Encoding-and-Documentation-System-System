package com.bred.rms.local;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.models.Company;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.models.Role;
import com.bred.rms.models.Supplier;
import com.bred.rms.models.User;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.AttachmentException;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.services.AuthException;
import com.bred.rms.services.ReceiptQuery;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModeTest {
    private static final String ADMIN = "admin@rms.local";
    private static final char[] ADMIN_PW = "admin123".toCharArray();

    private static Path newDir() throws IOException {
        Path d = Files.createTempDirectory("rms-local-test");
        d.toFile().deleteOnExit();
        return d;
    }

    private static AppContext adminContext(Path dir) throws Exception {
        AppContext ctx = AppContext.createLocal(dir);
        ctx.auth.login(ADMIN, ADMIN_PW.clone());
        return ctx;
    }

    private static Receipt receipt(UUID id, String no, Supplier s, Project p, List<Attachment> atts) {
        return new Receipt(id, no, LocalDate.of(2026, 11, 20), ReceiptType.OFFICIAL_RECEIPT, s.id(), p == null ? null : p.id(),
                List.of(new ReceiptItem("Nails", new BigDecimal("2"), new BigDecimal("100.50"))),
                BigDecimal.ZERO, new BigDecimal("24.12"), BigDecimal.ZERO, atts, null, null, null);
    }

    @Test
    void firstRunSeedsAnAdminWhoCanSignInAndWrongPasswordsFail() throws Exception {
        AppContext ctx = AppContext.createLocal(newDir());
        AuthException bad = assertThrows(AuthException.class, () -> ctx.auth.login(ADMIN, "nope".toCharArray()));
        assertEquals("Invalid email or password.", bad.getMessage());
        assertThrows(AuthException.class, () -> ctx.auth.login("", new char[0]));
        User u = ctx.auth.login(" Admin@RMS.local ", ADMIN_PW.clone());
        assertEquals(Role.ADMIN, u.role());
        assertEquals(u, ctx.auth.current());
        assertTrue(ctx.loginHint != null && ctx.loginHint.contains(ADMIN), String.valueOf(ctx.loginHint));
    }

    @Test
    void dataAndAttachmentsSurviveARestart() throws Exception {
        Path dir = newDir();
        Path scan = Files.createTempFile("scan", ".png");
        scan.toFile().deleteOnExit();
        byte[] bytes = {1, 2, 3, 4, 5};
        Files.write(scan, bytes);

        AppContext first = adminContext(dir);
        Supplier s = first.suppliers.save(new Supplier(null, "Sample Hardware", "000-111", "Davao", "0917"));
        Project p = first.projects.save(new Project(null, "Office Renovation", "PRJ-1", "General Fund"));
        UUID id = UUID.randomUUID();
        Attachment att = first.attachments.upload(id, scan);
        Receipt saved = first.receipts.save(receipt(id, "OR-1", s, p, List.of(att)));
        assertEquals(first.auth.current().id(), saved.encodedBy());
        assertEquals(first.auth.current().companyId(), saved.companyId());
        assertTrue(saved.createdAt() != null && saved.modifiedAt() != null);

        AppContext second = adminContext(dir);
        List<Receipt> all = second.receipts.search(ReceiptQuery.all());
        assertEquals(1, all.size());
        Receipt r = all.get(0);
        assertEquals("OR-1", r.receiptNo());
        assertEquals(second.auth.current().companyId(), r.companyId());
        assertEquals(new BigDecimal("225.12"), r.grandTotal());
        assertEquals(p.id(), r.projectId());
        assertEquals(1, r.attachments().size());
        assertArrayEquals(bytes, second.attachments.download(r.attachments().get(0)));
        assertEquals("Sample Hardware", second.suppliers.findAll().get(0).businessName());
        assertTrue(second.activityLog.findAll().stream().anyMatch(a -> a.action().equals("receipt.create") && a.detail().equals("OR-1")));
    }

    @Test
    void duplicatesAreRefused() throws Exception {
        AppContext ctx = adminContext(newDir());
        Supplier s = ctx.suppliers.save(new Supplier(null, "A", "123", "", ""));
        ValidationException tin = assertThrows(ValidationException.class, () -> ctx.suppliers.save(new Supplier(null, "B", " 123 ", "", "")));
        assertTrue(tin.getMessage().contains("TIN"), tin.getMessage());
        ctx.receipts.save(receipt(null, "OR-1", s, null, List.of()));
        ValidationException dup = assertThrows(ValidationException.class, () -> ctx.receipts.save(receipt(null, " or-1 ", s, null, List.of())));
        assertTrue(dup.getMessage().contains("Duplicate receipt"), dup.getMessage());
    }

    @Test
    void userRulesAreEnforced() throws Exception {
        AppContext ctx = adminContext(newDir());
        User admin = ctx.auth.current();
        RepositoryException last = assertThrows(RepositoryException.class, () -> ctx.users.update(admin.id(), "Admin", Role.ENCODER, true));
        assertTrue(last.getMessage().contains("last active administrator"), last.getMessage());
        User enc = ctx.users.create("Eli Encoder", "eli@x.test", Role.ENCODER, "secret1".toCharArray());
        assertThrows(RepositoryException.class, () -> ctx.users.create("Other", "ELI@x.test", Role.ENCODER, "secret1".toCharArray()));

        Supplier s = ctx.suppliers.save(new Supplier(null, "A", "1", "", ""));
        Receipt r = ctx.receipts.save(receipt(null, "OR-1", s, null, List.of()));
        ctx.auth.logout();
        assertEquals(null, ctx.auth.current());

        ctx.auth.login("eli@x.test", "secret1".toCharArray());
        assertThrows(SecurityException.class, () -> ctx.receipts.delete(r.id()));
        assertThrows(SecurityException.class, ctx.users::list);
        ctx.auth.logout();

        ctx.auth.login(ADMIN, ADMIN_PW.clone());
        ctx.users.update(enc.id(), "Eli Encoder", Role.ENCODER, false);
        AuthException inactive = assertThrows(AuthException.class, () -> ctx.auth.login("eli@x.test", "secret1".toCharArray()));
        assertTrue(inactive.getMessage().contains("inactive"), inactive.getMessage());
    }

    @Test
    void changedPasswordIsUsedFromThenOn() throws Exception {
        Path dir = newDir();
        AppContext ctx = adminContext(dir);
        assertThrows(AuthException.class, () -> ctx.auth.changePassword("12345"));
        ctx.auth.changePassword("brand-new-pw");
        ctx.auth.logout();
        assertThrows(AuthException.class, () -> ctx.auth.login(ADMIN, ADMIN_PW.clone()));
        AppContext again = AppContext.createLocal(dir);
        again.auth.login(ADMIN, "brand-new-pw".toCharArray());
        assertFalse(again.loginHint != null && again.loginHint.contains("admin123"), String.valueOf(again.loginHint));
    }

    @Test
    void directMessagesArePrivateAndSurviveARestart() throws Exception {
        Path dir = newDir();
        AppContext ctx = adminContext(dir);
        User sender = ctx.auth.current();
        User recipient = ctx.users.create("Eli Encoder", "eli@x.test", Role.ENCODER, "secret1".toCharArray());
        User other = ctx.users.create("Sam Encoder", "sam@x.test", Role.ENCODER, "secret1".toCharArray());

        var sent = ctx.messages.send(recipient.id(), "  Can you review this receipt?  ");
        assertEquals("Can you review this receipt?", sent.body());
        assertEquals(List.of(sent), ctx.messages.findFor(sender.id()));
        assertEquals(List.of(sent), ctx.messages.findFor(recipient.id()));
        assertTrue(ctx.messages.findFor(other.id()).isEmpty());
        assertThrows(RepositoryException.class, () -> ctx.messages.send(recipient.id(), "  "));

        AppContext reopened = adminContext(dir);
        assertEquals(List.of(sent), reopened.messages.findFor(sender.id()));
    }

    @Test
    void companyChatIsSharedUrgentAndSurvivesARestart() throws Exception {
        Path dir = newDir();
        AppContext ctx = adminContext(dir);
        User sender = ctx.auth.current();
        User teammate = ctx.users.create("Eli Encoder", "eli@x.test", Role.ENCODER, "secret1".toCharArray());
        assertEquals(sender.companyId(), teammate.companyId());

        ChatMessage urgent = ctx.messages.sendCompanyChat(sender.companyId(), "Meeting now", true);
        assertTrue(urgent.urgent());
        assertEquals(sender.companyId(), urgent.companyId());
        assertTrue(ctx.messages.findFor(sender.id()).isEmpty());
        ctx.auth.logout();
        User signedInTeammate = ctx.auth.login("eli@x.test", "secret1".toCharArray());
        assertEquals(List.of(urgent), ctx.messages.findCompanyChat(signedInTeammate.companyId()));
        assertThrows(SecurityException.class,
                () -> ctx.messages.findCompanyChat(UUID.randomUUID()));
        assertThrows(RepositoryException.class,
                () -> ctx.messages.sendCompanyChat(UUID.randomUUID(), "Wrong company", false));
        ctx.auth.logout();

        AppContext reopened = adminContext(dir);
        assertEquals(List.of(urgent), reopened.messages.findCompanyChat(sender.companyId()));
    }

    @Test
    void creatorBroadcastsAndDeletedUsersRemainInGlobalHistory() throws Exception {
        Path dir = newDir();
        AppContext ctx = adminContext(dir);
        char[] creatorPassword = "creator123".toCharArray();
        ctx.users.create("Clark Hilario", "clarkhilario.bread@gmail.com", Role.ENCODER, creatorPassword);
        ctx.auth.logout();
        User creator = ctx.auth.login("clarkhilario.bread@gmail.com", creatorPassword.clone());
        User recipient = ctx.users.create("Eli Encoder", "eli@x.test", Role.ENCODER, "secret1".toCharArray());

        ChatMessage message = ctx.messages.send(recipient.id(), "Private note");
        assertEquals(1, ctx.messageService.sendBroadcast(List.of(recipient.id()), "Office update"));
        assertEquals(1, ctx.messages.findBroadcastsFor(recipient.id(), java.time.Instant.EPOCH).size());

        ctx.users.delete(recipient.id());
        List<ChatMessage> history = ctx.messageService.findHistory();
        assertEquals(2, history.size());
        ChatMessage preserved = history.stream().filter(m -> m.id().equals(message.id())).findFirst().orElseThrow();
        assertEquals("Deleted user", preserved.recipientName());
        assertEquals(message.body(), preserved.body());

        ctx.auth.logout();
        AppContext reopened = AppContext.createLocal(dir);
        reopened.auth.login(creator.email(), creatorPassword.clone());
        assertEquals(2, reopened.messageService.findHistory().size());
    }

    @Test
    void creatorCanManageLocalCompaniesAndChangesSurviveRestart() throws Exception {
        Path dir = newDir();
        AppContext ctx = adminContext(dir);
        char[] password = "creator123".toCharArray();
        ctx.users.create("Clark Hilario", "clarkhilario.bread@gmail.com", Role.ENCODER, password);
        ctx.auth.logout();
        ctx.auth.login("clarkhilario.bread@gmail.com", password.clone());

        assertTrue(ctx.companies.isSupported());
        Company company = ctx.companies.list().get(0);
        assertEquals("Local Company", company.name());
        ctx.companies.update(company.id(), "Office");

        AppContext reopened = AppContext.createLocal(dir);
        reopened.auth.login("clarkhilario.bread@gmail.com", password.clone());
        assertEquals("Office", reopened.companies.list().get(0).name());
        reopened.companies.delete(company.id());
        assertTrue(reopened.companies.list().isEmpty());
    }

    @Test
    void deletingAReceiptRemovesItsFilesAndDownloadsCannotEscapeTheStore() throws Exception {
        Path dir = newDir();
        Path scan = Files.createTempFile("scan", ".pdf");
        scan.toFile().deleteOnExit();
        Files.write(scan, new byte[]{9});
        AppContext ctx = adminContext(dir);
        Supplier s = ctx.suppliers.save(new Supplier(null, "A", "1", "", ""));
        UUID id = UUID.randomUUID();
        Attachment att = ctx.attachments.upload(id, scan);
        ctx.receipts.save(receipt(id, "OR-1", s, null, List.of(att)));
        ctx.receipts.delete(id);
        assertTrue(ctx.receipts.search(ReceiptQuery.all()).isEmpty());
        assertThrows(AttachmentException.class, () -> ctx.attachments.download(att));
        assertThrows(AttachmentException.class, () -> ctx.attachments.download(new Attachment("x", "../data.json")));
        Path txt = Files.createTempFile("notes", ".txt");
        txt.toFile().deleteOnExit();
        assertThrows(AttachmentException.class, () -> ctx.attachments.upload(UUID.randomUUID(), txt));
    }

    @Test
    void aDamagedDataFileGivesAClearMessageAndIsNotOverwritten() throws Exception {
        Path dir = newDir();
        adminContext(dir);
        Path file = dir.resolve("data.json");
        Files.writeString(file, "{ this is not json");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppContext.createLocal(dir));
        assertTrue(e.getMessage().contains("data.json"), e.getMessage());
        assertEquals("{ this is not json", Files.readString(file));
    }
}
