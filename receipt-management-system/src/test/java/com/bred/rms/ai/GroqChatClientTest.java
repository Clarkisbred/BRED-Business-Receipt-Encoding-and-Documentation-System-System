package com.bred.rms.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroqChatClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = server(exchange -> {
            assertEquals("Bearer test-only-key", exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"call-1","type":"function","function":{"name":"searchReceipts",
                    "arguments":"{\\"query\\":\\"office\\"}"}}]}}]}
                    """);
        });
        var tool = new GroqChatClient.ToolDefinition("searchReceipts", "Search authorized receipts.",
                new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}"));
        GroqChatClient.Completion completion = client("test-model", "test-only-key")
                .complete(List.of(GroqChatClient.Message.text("user", "Find office receipts.")), List.of(tool));

        assertEquals(1, completion.toolCalls().size());
        assertEquals("searchReceipts", completion.toolCalls().get(0).name());
        assertEquals("office", completion.toolCalls().get(0).arguments().path("query").asText());
        assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
        assertTrue(requestBody.get().contains("\"tool_choice\":\"auto\""));
    }

    @Test
    void reportsAuthenticationRateLimitAndMalformedResponseErrorsWithoutSecrets() throws Exception {
        server = server(exchange -> respond(exchange, 401, "{\"error\":\"secret detail must not be surfaced\"}"));
        AIProviderException invalid = assertThrows(AIProviderException.class,
                () -> client("test-model", "test-only-key").complete(
                        List.of(GroqChatClient.Message.text("user", "hello")), List.of()));
        assertTrue(invalid.getMessage().contains("API key"));
        assertFalse(invalid.getMessage().contains("secret detail"));
        assertFalse(invalid.getMessage().contains("test-only-key"));

        server.stop(0);
        server = server(exchange -> respond(exchange, 429, "{}"));
        AIProviderException limited = assertThrows(AIProviderException.class,
                () -> client("test-model", "test-only-key").complete(
                        List.of(GroqChatClient.Message.text("user", "hello")), List.of()));
        assertTrue(limited.getMessage().contains("rate limit"));

        server.stop(0);
        server = server(exchange -> respond(exchange, 200, "{\"choices\":[]}"));
        assertThrows(AIProviderException.class, () -> client("test-model", "test-only-key").complete(
                List.of(GroqChatClient.Message.text("user", "hello")), List.of()));
    }

    @Test
    void rejectsMissingCredentialsAndMalformedToolArguments() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new GroqChatClient(HttpClient.newHttpClient(), serverUriOrLoopback(),
                        "model", new char[0], Duration.ofSeconds(1)));
        server = server(exchange -> respond(exchange, 200, """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                {"id":"call-1","type":"function","function":{"name":"searchReceipts","arguments":"[]"}}]}}]}
                """));
        assertThrows(AIProviderException.class, () -> client("model", "test-only-key").complete(
                List.of(GroqChatClient.Message.text("user", "hello")), List.of()));
    }

    @Test
    void enforcesTheConfiguredRequestTimeout() throws Exception {
        server = server(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        GroqChatClient client = new GroqChatClient(HttpClient.newHttpClient(), serverUriOrLoopback(),
                "model", "test-only-key".toCharArray(), Duration.ofMillis(40));

        AIProviderException timeout = assertThrows(AIProviderException.class,
                () -> client.complete(List.of(GroqChatClient.Message.text("user", "hello")), List.of()));

        assertTrue(timeout.getMessage().contains("timed out"));
        client.close();
    }

    private GroqChatClient client(String model, String key) {
        return new GroqChatClient(HttpClient.newHttpClient(), serverUriOrLoopback(), model, key.toCharArray(),
                Duration.ofSeconds(3));
    }

    private URI serverUriOrLoopback() {
        int port = server == null ? 12345 : server.getAddress().getPort();
        return URI.create("http://localhost:" + port + "/v1/chat/completions");
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
        return instance;
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
