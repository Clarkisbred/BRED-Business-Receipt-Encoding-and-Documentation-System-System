package com.bred.rms.api;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.BugReport;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.models.AppRelease;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.repositories.AppReleaseRepository;
import com.bred.rms.repositories.BugReportRepository;
import com.bred.rms.repositories.MessageRepository;
import com.bred.rms.repositories.UserRepository;
import com.bred.rms.security.Module;
import com.bred.rms.services.ProjectService;
import com.bred.rms.services.ReceiptService;
import com.bred.rms.services.SupplierService;
import com.bred.rms.support.Fakes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactWorkspaceServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class MemoryUsers implements UserRepository {
        private final List<User> users;
        private UUID passwordResetId;

        private MemoryUsers(List<User> users) {
            this.users = users;
        }

        @Override public List<User> findAll() { return users; }
        @Override public Optional<User> findById(UUID id) {
            return users.stream().filter(user -> user.id().equals(id)).findFirst();
        }
        @Override public User create(String email, String fullName, Role role, char[] password) {
            throw new UnsupportedOperationException();
        }
        @Override public void update(UUID id, String fullName, Role role, boolean active) {
            throw new UnsupportedOperationException();
        }
        @Override public void sendPasswordReset(UUID id) { passwordResetId = id; }
    }

    private static final class MemoryMessages implements MessageRepository {
        private final List<ChatMessage> privateMessages = new ArrayList<>();
        private final List<ChatMessage> companyMessages = new ArrayList<>();
        private List<UUID> broadcastRecipients = List.of();
        private String broadcastBody;

        @Override public List<ChatMessage> findFor(UUID userId) { return privateMessages; }
        @Override public List<ChatMessage> findCompanyChat(UUID companyId) { return companyMessages; }
        @Override public ChatMessage send(UUID recipientId, String body) {
            throw new UnsupportedOperationException();
        }
        @Override public ChatMessage sendCompanyChat(UUID companyId, String body, boolean urgent) {
            throw new UnsupportedOperationException();
        }
        @Override public int sendBroadcast(List<UUID> recipientIds, String body) {
            broadcastRecipients = List.copyOf(recipientIds);
            broadcastBody = body;
            return recipientIds.size();
        }
    }

    private static final class MemoryBugReports implements BugReportRepository {
        private final List<BugReport> reports = new ArrayList<>();

        @Override public void submit(UUID reporterId, String title, String description, String fileName,
                                     byte[] image, String contentType) {
            reports.add(new BugReport(UUID.randomUUID(), "Northstar", "Creator",
                    "creator@example.test", title, description, null, Instant.now()));
        }

        @Override public List<BugReport> findAll() { return reports; }
        @Override public byte[] downloadAttachment(String path) { throw new UnsupportedOperationException(); }
    }

    private static final class MemoryReleases implements AppReleaseRepository {
        private AppRelease release = new AppRelease("1.3.0", Instant.EPOCH);

        @Override public AppRelease current() { return release; }
        @Override public AppRelease publish(String version) {
            release = new AppRelease(version, Instant.now());
            return release;
        }
    }

    @Test
    void suppliesConversationIdsAndUrgentMetadataToReact() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        User user = new User(userId, "Current User", "current@example.test", Role.ADMIN, true,
                null, null, "Example Co", companyId);
        User contact = new User(contactId, "Active Contact", "contact@example.test", Role.ADMIN, true);
        User inactive = new User(UUID.randomUUID(), "Inactive Contact", "inactive@example.test", Role.ENCODER, false);
        MemoryMessages messages = new MemoryMessages();
        messages.privateMessages.add(new ChatMessage(UUID.randomUUID(), userId, contactId,
                "Private note", Instant.parse("2025-03-01T10:00:00Z"),
                false, "Current User", "current@example.test", "Active Contact", null, false));
        messages.companyMessages.add(new ChatMessage(UUID.randomUUID(), contactId, null,
                "Urgent update", Instant.parse("2025-03-01T11:00:00Z"),
                false, "Active Contact", "contact@example.test", "", companyId, true));

        ReactWorkspaceService service = service(user, List.of(user, contact, inactive), messages);
        LocalApiServer.ModulePageData page = service.load(Module.MESSAGES);

        assertEquals(1, page.fields().stream().filter(field -> "recipientId".equals(field.key()))
                .findFirst().orElseThrow().options().size());
        LocalApiServer.ModuleRecord direct = page.rows().stream()
                .filter(row -> "Private note".equals(row.values().get("body"))).findFirst().orElseThrow();
        assertEquals(userId, direct.values().get("senderId"));
        assertEquals(contactId, direct.values().get("recipientId"));
        LocalApiServer.ModuleRecord urgent = page.rows().stream()
                .filter(row -> "Urgent update".equals(row.values().get("body"))).findFirst().orElseThrow();
        assertEquals(companyId, urgent.values().get("companyId"));
        assertEquals(Boolean.TRUE, urgent.values().get("urgent"));
        assertFalse(Boolean.TRUE.equals(direct.values().get("urgent")));
    }

    @Test
    void sendsBroadcastsOnlyToActiveRecipientsWithCreatorAuthorization() throws Exception {
        UUID creatorId = UUID.randomUUID();
        UUID activeRecipientId = UUID.randomUUID();
        User creator = new User(creatorId, "Creator", "creator@example.test", Role.CREATOR, true);
        User activeRecipient = new User(activeRecipientId, "Active Recipient", "active@example.test", Role.ADMIN, true);
        User inactiveRecipient = new User(UUID.randomUUID(), "Inactive Recipient",
                "inactive@example.test", Role.ENCODER, false);
        MemoryMessages messages = new MemoryMessages();
        ReactWorkspaceService service = service(creator,
                List.of(creator, activeRecipient, inactiveRecipient), messages);
        String action = "{\"body\":\"  Company notice  \",\"broadcast\":true,\"recipientIds\":[\""
                + activeRecipientId + "\",\"" + activeRecipientId + "\"]}";

        service.apply(Module.MESSAGES, "send", null, JSON.readTree(action));

        assertEquals(List.of(activeRecipientId), messages.broadcastRecipients);
        assertEquals("Company notice", messages.broadcastBody);
        String inactiveAction = "{\"body\":\"Notice\",\"broadcast\":true,\"recipientIds\":[\""
                + inactiveRecipient.id() + "\"]}";
        assertThrows(SecurityException.class,
                () -> service.apply(Module.MESSAGES, "send", null, JSON.readTree(inactiveAction)));
        assertEquals(List.of(activeRecipientId), messages.broadcastRecipients);

        User admin = new User(UUID.randomUUID(), "Admin", "admin@example.test", Role.ADMIN, true);
        ReactWorkspaceService adminService = service(admin, List.of(admin, activeRecipient), messages);
        assertThrows(SecurityException.class,
                () -> adminService.apply(Module.MESSAGES, "send", null, JSON.readTree(action)));
        assertEquals("Company notice", messages.broadcastBody);
    }

    @Test
    void sendsPasswordResetOnlyForCreatorManagedUserAccounts() throws Exception {
        User creator = new User(UUID.randomUUID(), "Creator", "creator@example.test", Role.CREATOR, true);
        User target = new User(UUID.randomUUID(), "Account owner", "owner@example.test", Role.ENCODER, true);
        MemoryUsers users = new MemoryUsers(List.of(creator, target));
        AppContext context = AppContext.forTesting(creator, null, null, null, null,
                users, null, new MemoryMessages());
        ReactWorkspaceService service = new ReactWorkspaceService(context);

        service.apply(Module.USERS, "send-password-reset", target.id().toString(), JSON.readTree("{}"));

        assertEquals(target.id(), users.passwordResetId);
        assertThrows(IllegalArgumentException.class,
                () -> service.apply(Module.PROJECTS, "send-password-reset", target.id().toString(), JSON.readTree("{}")));

        User admin = new User(UUID.randomUUID(), "Admin", "admin@example.test", Role.ADMIN, true);
        ReactWorkspaceService adminService = service(admin, List.of(admin, target), new MemoryMessages());
        assertThrows(SecurityException.class,
                () -> adminService.apply(Module.USERS, "send-password-reset", target.id().toString(), JSON.readTree("{}")));
    }

    @Test
    void editingReceiptPersistsTheTypedCategory() throws Exception {
        User user = Fakes.encoder();
        Fakes.Receipts receiptRepository = new Fakes.Receipts();
        Fakes.Suppliers supplierRepository = new Fakes.Suppliers();
        Fakes.Projects projectRepository = new Fakes.Projects();
        Fakes.Attachments attachmentStore = new Fakes.Attachments();
        var supplier = supplierRepository.add("North Supply", "123-456");
        ReceiptService receiptService = new ReceiptService(receiptRepository, supplierRepository,
                projectRepository, attachmentStore, () -> user);
        Receipt existing = receiptService.save(Fakes.receipt("OR-EDIT", supplier, null,
                java.time.LocalDate.of(2026, 3, 10), "Cement", "500")
                .withClassification("UNCLASSIFIED"));
        AppContext context = AppContext.forTesting(user, receiptService,
                new SupplierService(supplierRepository, receiptRepository, () -> user),
                new ProjectService(projectRepository, receiptRepository, () -> user), attachmentStore);
        ReactWorkspaceService service = new ReactWorkspaceService(context);
        String input = "{\"receiptNo\":\"OR-EDIT\",\"date\":\"2026-03-10\","
                + "\"type\":\"official_receipt\",\"supplierId\":\"" + supplier.id() + "\","
                + "\"projectId\":\"\",\"classification\":\"materials\",\"paymentMethod\":\"Cash\","
                + "\"notes\":\"\",\"ewtRate\":\"0\",\"discount\":\"0\",\"vat\":\"0\","
                + "\"additionalCharges\":\"0\",\"items\":[{\"description\":\"Cement\","
                + "\"quantity\":\"1\",\"unit\":\"kg\",\"unitPrice\":\"500\"}],"
                + "\"attachments\":[],\"existingAttachmentPaths\":[]}";

        LocalApiServer.ModulePageData updated = service.apply(Module.RECEIPTS, "save",
                existing.id().toString(), JSON.readTree(input));

        assertEquals("MATERIALS", receiptService.find(existing.id()).orElseThrow().classification());
        assertEquals("MATERIALS", updated.rows().stream()
                .filter(row -> existing.id().toString().equals(row.id()))
                .findFirst().orElseThrow().values().get("classification"));
    }

    @Test
    void exposesCreatorSettingsAndSubmitsBugReportsThroughTheValidatedService() throws Exception {
        User creator = new User(UUID.randomUUID(), "Creator", "creator@example.test", Role.CREATOR, true);
        MemoryBugReports reports = new MemoryBugReports();
        AppContext context = AppContext.forTesting(creator, null, null, null, null,
                null, null, new MemoryMessages(), reports);
        ReactWorkspaceService service = new ReactWorkspaceService(context);

        LocalApiServer.ModulePageData settings = service.load(Module.SETTINGS);
        assertTrue(settings.metrics().stream().anyMatch(metric -> "Installed version".equals(metric.label())
                && !metric.value().isBlank()));
        assertTrue(settings.metrics().stream().anyMatch(metric -> "Company".equals(metric.label())
                && "All companies".equals(metric.value())));
        assertTrue(settings.metrics().stream().anyMatch(metric -> "Bug report submissions".equals(metric.label())
                && "Available".equals(metric.value())));
        assertTrue(settings.fields().stream().anyMatch(field -> "appearance".equals(field.key())
                && field.options().size() == com.bred.rms.ui.theme.ThemeManager.Appearance.values().length));

        LocalApiServer.ModulePageData updated = service.apply(Module.SETTINGS, "report", null,
                JSON.readTree("{\"title\":\"Theme issue\",\"description\":\"Preset did not persist.\"}"));

        assertEquals(1, reports.reports.size());
        assertEquals("Theme issue", reports.reports.get(0).title());
        assertEquals("Preset did not persist.", reports.reports.get(0).description());
        assertEquals("Theme issue", updated.rows().get(0).values().get("title"));
    }

    @Test
    void savesThemePresetAndColorModeForTheSharedJavaTheme() throws Exception {
        User creator = new User(UUID.randomUUID(), "Creator", "creator@example.test", Role.CREATOR, true);
        AppContext context = AppContext.forTesting(creator, null, null, null, null,
                null, null, new MemoryMessages());
        ReactWorkspaceService service = new ReactWorkspaceService(context);
        com.bred.rms.ui.theme.ThemeManager.Appearance originalAppearance =
                com.bred.rms.ui.theme.ThemeManager.appearance();
        boolean originalDark = com.bred.rms.ui.theme.ThemeManager.isDark();

        try {
            LocalApiServer.ModulePageData updated = service.apply(Module.SETTINGS, "save", null,
                    JSON.readTree("{\"appearance\":\"DEFAULT\",\"dark\":false,"
                            + "\"newPassword\":\"\",\"confirmPassword\":\"\"}"));

            assertEquals("DEFAULT", com.bred.rms.ui.theme.ThemeManager.reactTheme().appearance());
            assertFalse(com.bred.rms.ui.theme.ThemeManager.reactTheme().dark());
            assertTrue(updated.fields().stream().anyMatch(field -> "dark".equals(field.key())
                    && Boolean.FALSE.equals(field.value())));
        } finally {
            com.bred.rms.ui.theme.ThemeManager.setAppearance(originalAppearance);
            com.bred.rms.ui.theme.ThemeManager.apply(originalDark);
        }
    }

    @Test
    void publishesReleaseThroughTheCreatorAuthorizedJavaService() throws Exception {
        User creator = new User(UUID.randomUUID(), "Creator", "creator@example.test", Role.CREATOR, true);
        MemoryReleases releases = new MemoryReleases();
        AppContext context = AppContext.forTesting(creator, null, null, null, null,
                null, null, new MemoryMessages(), null, releases);
        ReactWorkspaceService service = new ReactWorkspaceService(context);

        LocalApiServer.ModulePageData updated = service.apply(Module.SETTINGS, "publish-release", null,
                JSON.readTree("{\"version\":\"1.4.0\"}"));

        assertEquals("1.4.0", releases.current().version());
        assertEquals("1.4.0", updated.metrics().stream()
                .filter(metric -> "Latest release".equals(metric.label()))
                .findFirst().orElseThrow().value());
    }

    private static ReactWorkspaceService service(User currentUser, List<User> users, MemoryMessages messages) {
        AppContext context = AppContext.forTesting(currentUser, null, null, null, null,
                new MemoryUsers(users), null, messages);
        return new ReactWorkspaceService(context);
    }
}
