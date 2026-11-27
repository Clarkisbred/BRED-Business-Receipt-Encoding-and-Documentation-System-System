package com.bred.rms.supabase;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bred.rms.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Single place that talks HTTP to Supabase. Sends only the public anon key plus the caller's JWT. */
public final class SupabaseHttp {
    /** Shared mapper: decimals stay exact (BigDecimal) and are written as plain numbers. */
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    /** Status used in {@link SupabaseException} for "the app is not configured". */
    public static final int NOT_CONFIGURED = -1;
    /** Status used in {@link SupabaseException} for "the server answered with something unreadable". */
    public static final int BAD_RESPONSE = -2;

    private final AppConfig config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Creates a supabase http instance. */
    public SupabaseHttp(AppConfig config) { this.config = config; }

    /** Serialises any object (maps, records, JsonNodes) to JSON text with the shared mapper. */
    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build the request: " + e.getMessage(), e);
        }
    }

    /** @param path path beginning with "/", e.g. "/rest/v1/receipts?select=*"
     *  @param accessToken user JWT, or null to use the anon key */
    public JsonNode send(String method, String path, String jsonBody, String accessToken, Map<String, String> headers)
            throws SupabaseException {
        byte[] body = jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8);
        byte[] res = exchange(method, path, body, jsonBody == null ? null : "application/json", accessToken, headers, "application/json");
        if (res.length == 0 || new String(res, StandardCharsets.UTF_8).isBlank()) return JSON.nullNode();
        try {
            return JSON.readTree(res);
        } catch (IOException e) {
            throw new SupabaseException(BAD_RESPONSE, "The server sent an unexpected response. Please try again.");
        }
    }

    /** Raw-bytes variant used for Storage uploads and downloads. Returns the response body (possibly empty). */
    public byte[] sendBytes(String method, String path, byte[] body, String contentType, String accessToken,
                            Map<String, String> headers) throws SupabaseException {
        return exchange(method, path, body, contentType, accessToken, headers, "*/*");
    }

    /** Performs the request and returns the body of a 2xx response; anything else becomes a SupabaseException. */
    private byte[] exchange(String method, String path, byte[] body, String contentType, String accessToken,
                            Map<String, String> headers, String accept) throws SupabaseException {
        if (!config.isConfigured()) {
            throw new SupabaseException(NOT_CONFIGURED,
                    "Cloud Database is not configured. Contact your administrator.");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.supabaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("apikey", config.anonKey())
                .header("Authorization", "Bearer " + (accessToken != null ? accessToken : config.anonKey()))
                .header("Accept", accept);
        if (headers != null) headers.forEach(b::header);
        if (contentType != null) b.header("Content-Type", contentType);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> res = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] out = res.body() == null ? new byte[0] : res.body();
            if (res.statusCode() / 100 != 2) {
                throw new SupabaseException(res.statusCode(), extractMessage(new String(out, StandardCharsets.UTF_8), res.statusCode()));
            }
            return out;
        } catch (IOException e) {
            throw new SupabaseException(0, "Can't reach the server. Check your internet connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupabaseException(0, "Request interrupted");
        }
    }

    /** Extracts a human-readable error message from a Supabase response body. */
    private static String extractMessage(String body, int status) {
        try {
            JsonNode n = JSON.readTree(body);
            for (String f : new String[] {"message", "msg", "error_description", "error"}) {
                if (n != null && n.hasNonNull(f)) return n.get(f).asText();
            }
        } catch (IOException ignored) { /* fall through */ }
        return "Request failed (HTTP " + status + ")";
    }
}
