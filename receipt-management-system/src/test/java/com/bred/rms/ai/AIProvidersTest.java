package com.bred.rms.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIProvidersTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void localProviderChecksAvailabilityAndUsesOllamaGenerateApi() throws Exception {
        server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/tags")) {
                respond(exchange, 200, "{\"models\":[]}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"model\":\"qwen2.5:3b\""));
            assertTrue(body.contains("\"stream\":false"));
            respond(exchange, 200, "{\"response\":\"generated locally\"}");
        });
        LocalAIProvider provider = localProvider();

        assertTrue(provider.isAvailable());
        assertEquals("generated locally", provider.generate(new AIRequest("hello", false)));
    }

    @Test
    void providerReportsHttpFailuresAndMalformedResponses() throws Exception {
        server = server(exchange -> respond(exchange, 503, "{\"error\":\"unavailable\"}"));
        LocalAIProvider unavailable = localProvider();
        assertFalse(unavailable.isAvailable());
        assertThrows(AIProviderException.class, () -> unavailable.generate(new AIRequest("hello", false)));

        server.stop(0);
        server = server(exchange -> respond(exchange, 200, "{}"));
        assertThrows(AIProviderException.class,
                () -> localProvider().generate(new AIRequest("hello", false)));
    }

    @Test
    void localProviderEnforcesRequestTimeout() throws Exception {
        server = server(exchange -> {
            try {
                Thread.sleep(400);
                respond(exchange, 200, "{\"response\":\"late\"}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        LocalAIProvider provider = new LocalAIProvider(
                HttpClient.newHttpClient(), serverUri(), "qwen2.5:3b", Duration.ofMillis(50));

        AIProviderException failure = org.junit.jupiter.api.Assertions.assertThrows(
                AIProviderException.class, () -> provider.generate(new AIRequest("hello", false)));
        assertTrue(failure.getMessage().contains("timed out"));
    }

    @Test
    void cloudProviderRequiresConfiguredCredentialsAndBlocksAttachmentsWithoutConsent() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"cloud result\"}}]}");
        });
        CloudAIProvider missingCredentials = new CloudAIProvider(HttpClient.newHttpClient(), serverUri(),
                null, "model", Duration.ofSeconds(3), false);
        assertFalse(missingCredentials.isAvailable());
        assertThrows(AIProviderException.class,
                () -> missingCredentials.generate(new AIRequest("hello", false)));

        CloudAIProvider guarded = cloudProvider(false);
        assertThrows(AIProviderException.class,
                () -> guarded.generate(new AIRequest("text prompt", true)));
        assertEquals(0, requests.get());
    }

    @Test
    void cloudProviderUsesChatCompletionsWhenConfigured() throws Exception {
        server = server(exchange -> {
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"model\":\"test-model\""));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"cloud result\"}}]}");
        });

        assertEquals("cloud result", cloudProvider(true).generate(new AIRequest("hello", true)));
    }

    @Test
    void hybridProviderFallsBackToCloudOnlyWhenLocalGenerationFails() throws Exception {
        server = server(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"cloud fallback\"}}]}"));
        AIProvider localFailure = new AIProvider() {
            @Override
            public String name() { return "test-local"; }
            @Override
            public boolean isAvailable() { return false; }
            @Override
            public String generate(AIRequest request) throws AIProviderException {
                throw new AIProviderException("local offline");
            }
        };

        HybridAIProvider hybrid = new HybridAIProvider(localFailure, cloudProvider(false));
        assertTrue(hybrid.isAvailable());
        assertEquals("cloud fallback", hybrid.generate(new AIRequest("hello", false)));
    }

    private LocalAIProvider localProvider() {
        return new LocalAIProvider(HttpClient.newHttpClient(), serverUri(), "qwen2.5:3b", Duration.ofSeconds(3));
    }

    private CloudAIProvider cloudProvider(boolean consent) {
        return new CloudAIProvider(HttpClient.newHttpClient(), serverUri(), "test-key", "test-model",
                Duration.ofSeconds(3), consent);
    }

    private URI serverUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
