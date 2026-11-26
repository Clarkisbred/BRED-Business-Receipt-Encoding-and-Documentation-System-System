package com.bred.rms.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Groq's OpenAI-compatible chat-completions client. It never logs request headers or bodies. */
public final class GroqChatClient implements AutoCloseable {
    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message text(String role, String content) {
            return new Message(role, content, null, List.of());
        }
    }

    public record ToolCall(String id, String name, JsonNode arguments) { }
    public record ToolDefinition(String name, String description, JsonNode parameters) { }
    public record Completion(String content, List<ToolCall> toolCalls) {
        public Completion {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    private final HttpClient http;
    private final URI endpoint;
    private final String model;
    private final char[] apiKey;
    private final Duration timeout;
    private boolean closed;

    public GroqChatClient(String model, char[] apiKey, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), DEFAULT_ENDPOINT, model, apiKey, timeout);
    }

    GroqChatClient(HttpClient http, URI endpoint, String model, char[] apiKey, Duration timeout) {
        if (http == null || endpoint == null || model == null || model.isBlank()
                || apiKey == null || apiKey.length == 0 || timeout == null || timeout.isNegative()
                || timeout.isZero()) {
            throw new IllegalArgumentException("Groq requires an endpoint, model, API key and positive timeout.");
        }
        this.http = http;
        this.endpoint = endpoint;
        this.model = model.trim();
        this.apiKey = apiKey.clone();
        this.timeout = timeout;
    }

    public Completion complete(List<Message> messages, List<ToolDefinition> tools)
            throws AIProviderException {
        if (closed) throw new IllegalStateException("The Groq client is closed.");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("At least one chat message is required.");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        ArrayNode requestMessages = payload.putArray("messages");
        for (Message message : messages) {
            ObjectNode item = requestMessages.addObject().put("role", message.role());
            if (message.content() != null) item.put("content", message.content());
            if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = item.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode function = calls.addObject().put("id", call.id()).put("type", "function")
                            .putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments().toString());
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode requestTools = payload.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode function = requestTools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.parameters());
            }
            payload.put("tool_choice", "auto");
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + new String(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw statusFailure(response.statusCode());
            }
            return parseCompletion(response.body());
        } catch (java.net.http.HttpTimeoutException error) {
            throw new AIProviderException("Groq did not respond before the request timed out.", error);
        } catch (IOException error) {
            throw new AIProviderException("Could not communicate with Groq. Check the connection and try again.",
                    error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AIProviderException("The Groq request was interrupted.", error);
        }
    }

    private static Completion parseCompletion(String body) throws AIProviderException {
        try {
            JsonNode choices = JSON.readTree(body).path("choices");
            if (!choices.isArray() || choices.isEmpty() || !choices.path(0).path("message").isObject()) {
                throw new AIProviderException("Groq returned a malformed chat-completions response.");
            }
            JsonNode message = choices.path(0).path("message");
            JsonNode content = message.path("content");
            String text = content.isTextual() ? content.asText() : null;
            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isMissingNode()) {
                if (!toolCalls.isArray()) {
                    throw new AIProviderException("Groq returned malformed tool-call data.");
                }
                for (JsonNode call : toolCalls) {
                    String id = call.path("id").asText("");
                    JsonNode function = call.path("function");
                    String name = function.path("name").asText("");
                    JsonNode arguments = JSON.readTree(function.path("arguments").asText(""));
                    if (id.isBlank() || name.isBlank() || !arguments.isObject()) {
                        throw new AIProviderException("Groq returned a malformed tool call.");
                    }
                    calls.add(new ToolCall(id, name, arguments));
                }
            }
            if ((text == null || text.isBlank()) && calls.isEmpty()) {
                throw new AIProviderException("Groq returned an empty response.");
            }
            return new Completion(text, calls);
        } catch (IOException | RuntimeException error) {
            throw new AIProviderException("Groq returned a malformed chat-completions response.", error);
        }
    }

    private static AIProviderException statusFailure(int status) {
        return switch (status) {
            case 401, 403 -> new AIProviderException("Groq rejected the API key. Check your saved credentials.");
            case 429 -> new AIProviderException("Groq rate limit reached. Wait briefly, then try again.");
            case 408, 504 -> new AIProviderException("Groq request timed out. Try again.");
            default -> status >= 500
                    ? new AIProviderException("Groq is temporarily unavailable (HTTP " + status + ").")
                    : new AIProviderException("Groq rejected the request (HTTP " + status + ").");
        };
    }

    @Override
    public void close() {
        java.util.Arrays.fill(apiKey, '\0');
        closed = true;
    }
}
