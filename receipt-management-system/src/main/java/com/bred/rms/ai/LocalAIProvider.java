package com.bred.rms.ai;

import com.bred.rms.config.AiImportConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Ollama-backed local provider using Ollama's native HTTP API. */
public final class LocalAIProvider implements AIProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI baseUrl;
    private final String model;
    private final Duration timeout;

    public LocalAIProvider(AiImportConfig config) {
        this(HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(),
                config.ollamaBaseUrl(), config.ollamaModel(), config.requestTimeout());
    }

    LocalAIProvider(HttpClient httpClient, URI baseUrl, String model, Duration timeout) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("api/tags"))
                    .timeout(timeout)
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
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.put("prompt", request.prompt());
        payload.put("stream", false);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint("api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), "Ollama");
            JsonNode result = JSON.readTree(response.body());
            JsonNode generatedText = result.get("response");
            if (generatedText == null || !generatedText.isTextual()) {
                throw new AIProviderException("Ollama returned a response without generated text.");
            }
            return generatedText.asText();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AIProviderException("Ollama request timed out.", e);
        } catch (IOException e) {
            throw new AIProviderException("Could not communicate with Ollama or parse its response.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AIProviderException("Ollama request was interrupted.", e);
        }
    }

    private URI endpoint(String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + path);
    }

    private static void requireSuccess(int statusCode, String provider) throws AIProviderException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new AIProviderException(provider + " returned HTTP status " + statusCode + ".");
        }
    }
}
