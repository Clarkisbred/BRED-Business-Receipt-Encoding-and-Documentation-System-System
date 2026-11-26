package com.bred.rms.ai;

import com.bred.rms.config.AppContext;
import com.bred.rms.importer.DocumentTextExtractor;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.services.CurrentUser;
import com.bred.rms.services.ProjectService;
import com.bred.rms.services.ReceiptService;
import com.bred.rms.services.SupplierService;
import com.bred.rms.support.Fakes;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroqDuckyServiceTest {
    @TempDir
    Path temporaryDirectory;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void requiresCloudConsentAndReportsGroqErrorsWithoutAnOllamaFallback() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        try {
            DuckyPreferences.setCloudConsent(false);
            GroqDuckyService service = service(null, null);
            AIProviderException consent = assertThrows(AIProviderException.class,
                    () -> service.respond("hello", List.of(), List.of(), (a, b) -> true, ignored -> { }));
            assertTrue(consent.getMessage().contains("privacy notice"));

            DuckyPreferences.setCloudConsent(true);
            GroqDuckyService missingKey = service(null, null, new GroqCredentials(Map.of(), new MemoryStore(null)));
            AIProviderException missing = assertThrows(AIProviderException.class,
                    () -> missingKey.respond("hello", List.of(), List.of(), (a, b) -> true, ignored -> { }));
            assertTrue(missing.getMessage().contains("API key"));
            AtomicInteger requests = new AtomicInteger();
            server = server(exchange -> {
                requests.incrementAndGet();
                respond(exchange, 503, "{\"error\":\"offline\"}");
            });
            AIProviderException unavailable = assertThrows(AIProviderException.class,
                    () -> service().respond("hello", List.of(), List.of(), (a, b) -> true, ignored -> { }));
            assertTrue(unavailable.getMessage().contains("temporarily unavailable"));
            assertEquals(1, requests.get());
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
        }
    }

    @Test
    void keepsOcrLocalWhenUserDeclinesExplicitSharing() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        try {
            DuckyPreferences.setCloudConsent(true);
            AtomicInteger requests = new AtomicInteger();
            server = server(exchange -> {
                requests.incrementAndGet();
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"cloud answer\"}}]}");
            });
            Path file = temporaryDirectory.resolve("fictional.csv");
            Files.writeString(file, "Item,Quantity,Unit Price\nNotebook,2,25.00\n");
            AtomicReference<String> disclosure = new AtomicReference<>();

            GroqDuckyService.AgentResponse result = service().respond("Summarize this document.",
                    List.of(), List.of(file), (title, details) -> {
                        disclosure.set(details);
                        return false;
                    }, ignored -> { });

            assertEquals(0, requests.get());
            assertTrue(disclosure.get().contains("Notebook"));
            assertTrue(result.localResults().get(0).contains("Notebook"));
            assertTrue(result.answer().contains("did not send"));
            assertFalse(result.privateDataDisclosed());
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
        }
    }

    @Test
    void extractsAttachmentsLocallyWithoutCloudConsentOrCredentials() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        try {
            DuckyPreferences.setCloudConsent(false);
            Path file = temporaryDirectory.resolve("local-only.csv");
            Files.writeString(file, "Item,Quantity,Unit Price\nNotebook,2,25.00\n");

            GroqCredentials noCredentials = new GroqCredentials(Map.of(), new MemoryStore(null));
            GroqDuckyService.AgentResponse result = service(null, null, noCredentials).respond(
                    "Read this document.", List.of(), List.of(file), null, ignored -> { });

            assertTrue(result.answer().contains("not sent anywhere"));
            assertTrue(result.localResults().get(0).contains("Notebook"));
            assertFalse(result.privateDataDisclosed());
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
        }
    }

    @Test
    void doesNotReturnBredResultsToGroqWithoutPerResultApproval() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        try {
            DuckyPreferences.setCloudConsent(true);
            DuckyPreferences.setPermission(DuckyPermission.ASSIST);
            Fixture fixture = new Fixture();
            var supplier = fixture.suppliers.add("Example Supplier", "TIN-PRIVATE-111");
            var receipt = Fakes.receipt("FICTITIOUS-RECEIPT-91", supplier, null,
                    LocalDate.of(2026, 1, 2), "Private item", "125.00");
            fixture.receipts.data.put(receipt.id(), receipt);
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<String> secondRequest = new AtomicReference<>();
            server = server(exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (requests.incrementAndGet() == 1) {
                    respond(exchange, 200, toolCall("call-1", "searchReceipts", "{\"query\":\"FICTITIOUS\"}"));
                } else {
                    secondRequest.set(body);
                    respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"I kept it local.\"}}]}");
                }
            });
            AtomicInteger confirmations = new AtomicInteger();

            GroqDuckyService.AgentResponse response = service(fixture.context).respond(
                    "Find my receipt.", List.of(), List.of(), (title, details) -> {
                        confirmations.incrementAndGet();
                        assertTrue(details.contains("FICTITIOUS-RECEIPT-91"));
                        return false;
                    }, ignored -> { });

            assertEquals(1, confirmations.get());
            assertEquals(2, requests.get());
            assertFalse(secondRequest.get().contains("FICTITIOUS-RECEIPT-91"));
            assertFalse(secondRequest.get().contains("TIN-PRIVATE-111"));
            assertTrue(response.localResults().get(0).contains("FICTITIOUS-RECEIPT-91"));
            assertFalse(response.privateDataDisclosed());
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
        }
    }

    @Test
    void stopsRepeatedToolLoopsAndCapsOperationsPerRequest() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        try {
            DuckyPreferences.setCloudConsent(true);
            DuckyPreferences.setPermission(DuckyPermission.ASSIST);
            AtomicInteger requests = new AtomicInteger();
            server = server(exchange -> {
                requests.incrementAndGet();
                respond(exchange, 200, toolCall("loop", "getApplicationVersion", "{}"));
            });
            AIProviderException loop = assertThrows(AIProviderException.class,
                    () -> service().respond("Check version.", List.of(), List.of(), (a, b) -> true, ignored -> { }));
            assertTrue(loop.getMessage().contains("repeated tool request"));
            assertEquals(2, requests.get());

            server.stop(0);
            AtomicReference<String> response = new AtomicReference<>();
            server = server(exchange -> {
                StringBuilder calls = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    calls.append("{\"id\":\"call-").append(i)
                            .append("\",\"type\":\"function\",\"function\":{\"name\":\"getApplicationVersion\",")
                            .append("\"arguments\":\"{\\\"extra\\\":").append(i).append("}\"}}");
                    if (i < 5) calls.append(',');
                }
                response.set("{\"choices\":[{\"message\":{\"tool_calls\":[" + calls + "]}}]}");
                respond(exchange, 200, response.get());
            });
            GroqDuckyService.AgentResponse capped = service().respond("Check versions.", List.of(), List.of(),
                    (a, b) -> true, ignored -> { });
            assertTrue(capped.answer().contains("stopped after 5"));
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
        }
    }

    @Test
    void sendsPrivateResultsAndOcrOnlyAfterTheSpecificDisclosureIsApproved() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        try {
            DuckyPreferences.setCloudConsent(true);
            DuckyPreferences.setPermission(DuckyPermission.ASSIST);
            Fixture fixture = new Fixture();
            var supplier = fixture.suppliers.add("Example Supplier", "TIN-PRIVATE-333");
            var receipt = Fakes.receipt("FICTITIOUS-RECEIPT-92", supplier, null,
                    LocalDate.of(2026, 1, 2), "Private item", "55.00");
            fixture.receipts.data.put(receipt.id(), receipt);
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<String> secondRequest = new AtomicReference<>();
            server = server(exchange -> {
                if (requests.incrementAndGet() == 1) {
                    respond(exchange, 200, toolCall("call-1", "searchReceipts", "{\"query\":\"FICTITIOUS\"}"));
                } else {
                    secondRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Receipt found.\"}}]}");
                }
            });

            GroqDuckyService.AgentResponse result = service(fixture.context).respond(
                    "Find my receipt.", List.of(), List.of(), (title, details) -> {
                        assertTrue(details.contains("FICTITIOUS-RECEIPT-92"));
                        return true;
                    }, ignored -> { });

            assertTrue(secondRequest.get().contains("FICTITIOUS-RECEIPT-92"));
            assertTrue(result.privateDataDisclosed());
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
        }
    }

    @Test
    void executesNoUnregisteredOrMalformedToolCall() throws Exception {
        boolean oldConsent = DuckyPreferences.cloudConsent();
        DuckyPermission oldPermission = DuckyPreferences.permission();
        try {
            DuckyPreferences.setCloudConsent(true);
            DuckyPreferences.setPermission(DuckyPermission.ASSIST);
            AtomicInteger requests = new AtomicInteger();
            server = server(exchange -> {
                if (requests.incrementAndGet() == 1) {
                    respond(exchange, 200, toolCall("call-1", "deleteReceipt", "{}"));
                } else {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(body.contains("not authorized"));
                    respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"No change was made.\"}}]}");
                }
            });
            assertEquals("No change was made.", service().respond("Delete this receipt.", List.of(), List.of(),
                    (a, b) -> true, ignored -> { }).answer());
            assertEquals(2, requests.get());
        } finally {
            DuckyPreferences.setCloudConsent(oldConsent);
            if (oldConsent) DuckyPreferences.setPermission(oldPermission);
        }
    }

    private GroqDuckyService service() {
        return service(null, serverUri());
    }

    private GroqDuckyService service(AppContext context) {
        return service(context, serverUri());
    }

    private GroqDuckyService service(AppContext context, URI endpoint) {
        GroqCredentials credentials = new GroqCredentials(Map.of(), new MemoryStore("test-only-key"));
        return service(context, endpoint, credentials);
    }

    private GroqDuckyService service(AppContext context, URI endpoint, GroqCredentials credentials) {
        DocumentTextExtractor extractor = new DocumentTextExtractor((image, temporary) -> "unused");
        return new GroqDuckyService(context, credentials, ignored -> { }, ignored -> { },
                Duration.ofSeconds(3), temporaryDirectory, extractor, endpoint);
    }

    private static String toolCall(String id, String name, String arguments) {
        String encoded = arguments.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                + "\",\"arguments\":\"" + encoded + "\"}}]}}]}";
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer instance = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        instance.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        instance.start();
        server = instance;
        return instance;
    }

    private URI serverUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }

    private static User approvedUser() {
        return new User(UUID.randomUUID(), "Test Admin", "admin@example.test", Role.ADMIN, true,
                AccountStatus.APPROVED, Instant.EPOCH, "Test Co", UUID.randomUUID());
    }

    private static final class Fixture {
        private final Fakes.Receipts receipts = new Fakes.Receipts();
        private final Fakes.Suppliers suppliers = new Fakes.Suppliers();
        private final Fakes.Projects projects = new Fakes.Projects();
        private final Fakes.Attachments attachments = new Fakes.Attachments();
        private final AppContext context;

        private Fixture() {
            User user = approvedUser();
            CurrentUser current = () -> user;
            context = AppContext.forTesting(user,
                    new ReceiptService(receipts, suppliers, projects, attachments, current),
                    new SupplierService(suppliers, receipts, current),
                    new ProjectService(projects, receipts, current), attachments);
        }
    }

    private static final class MemoryStore implements GroqCredentialStore {
        private char[] secret;

        private MemoryStore(String initial) {
            secret = initial == null ? null : initial.toCharArray();
        }

        @Override public char[] read() { return secret == null ? null : secret.clone(); }

        @Override public void save(char[] apiKey) {
            if (secret != null) Arrays.fill(secret, '\0');
            secret = apiKey.clone();
        }

        @Override public void delete() {
            if (secret != null) Arrays.fill(secret, '\0');
            secret = null;
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
