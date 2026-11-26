package com.bred.rms.ai;

import com.bred.rms.config.AiImportConfig;
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

/** Optional OpenAI-compatible cloud chat-completions provider. */
public final class CloudAIProvider implements AIProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final boolean attachmentsConsent;

    public CloudAIProvider(AiImportConfig config) {
        this(HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(),
                config.cloudBaseUrl().orElse(null), config.cloudApiKey().orElse(null), config.cloudModel(),
                config.requestTimeout(), config.cloudAttachmentsConsent());
    }

    CloudAIProvider(HttpClient httpClient, URI baseUrl, String apiKey, String model, Duration timeout,
                    boolean attachmentsConsent) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.attachmentsConsent = attachmentsConsent;
    }

    @Override
    public String name() {
        return "cloud";
    }

    @Override
    public boolean isAvailable() {
        if (baseUrl == null || apiKey == null || apiKey.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("v1/models"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public String generate(AIRequest request) throws AIProviderException {
        if (request.hasAttachments() && !attachmentsConsent) {
            throw new AIProviderException(
                    "Cloud AI cannot be used with attachments unless an administrator explicitly enables consent.");
        }
        if (baseUrl == null) {
            throw new AIProviderException("Cloud AI is not configured: RMS_CLOUD_AI_BASE_URL is missing.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIProviderException("Cloud AI is not configured: RMS_CLOUD_AI_API_KEY is missing.");
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "user").put("content", request.prompt());
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint("v1/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AIProviderException("Cloud AI returned HTTP status " + response.statusCode() + ".");
            }
            JsonNode content = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new AIProviderException("Cloud AI returned a response without generated text.");
            }
            return content.asText();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AIProviderException("Cloud AI request timed out.", e);
        } catch (IOException e) {
            throw new AIProviderException("Could not communicate with cloud AI or parse its response.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIProviderException("Cloud AI request was interrupted.", e);
        }
    }

    private URI endpoint(String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + path);
    }
}
