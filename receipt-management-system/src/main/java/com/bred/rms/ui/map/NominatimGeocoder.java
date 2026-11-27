package com.bred.rms.ui.map;

import com.bred.rms.config.AppVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Nominatim forward geocoder for project location search. */
public final class NominatimGeocoder {
    public record Result(String displayName, double latitude, double longitude,
                         String type, String osmType, long osmId) {}

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofSeconds(1);

    private final HttpClient http;
    private final URI endpoint;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, List<Result>> cache = new ConcurrentHashMap<>();
    private Instant lastRequest = Instant.EPOCH;

    public NominatimGeocoder() {
        this(defaultEndpoint());
    }

    NominatimGeocoder(URI endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    NominatimGeocoder(URI endpoint, HttpClient http) {
        if (endpoint == null) throw new IllegalArgumentException("Nominatim endpoint is required.");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Nominatim endpoint must use HTTP or HTTPS.");
        }
        this.endpoint = endpoint;
        this.http = http;
    }

    public List<Result> search(String query) throws IOException, InterruptedException {
        if (query == null || query.trim().isEmpty()) return List.of();
        String key = query.trim().toLowerCase(Locale.ROOT);
        List<Result> cached = cache.get(key);
        if (cached != null) return List.copyOf(cached);

        synchronized (this) {
            Duration wait = Duration.between(Instant.now(), lastRequest.plus(MIN_REQUEST_INTERVAL));
            long millis = wait.toMillis();
            if (millis > 0) Thread.sleep(millis);
            lastRequest = Instant.now();
        }

        String url = endpoint.toString()
                + (endpoint.toString().contains("?") ? "&" : "?")
                + "format=jsonv2"
                + "&addressdetails=1"
                + "&limit=5"
                + "&q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "BRED/" + AppVersion.current() + " (Windows desktop project map)")
                .header("Accept", "application/json")
                .header("Accept-Language", "en")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new IOException("Nominatim is rate-limited. Try again in a moment.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Nominatim returned HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        if (!root.isArray()) throw new IOException("Nominatim returned an invalid response.");

        List<Result> results = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.hasNonNull("lat") || !item.hasNonNull("lon")) continue;
            try {
                results.add(new Result(
                        item.path("display_name").asText(""),
                        Double.parseDouble(item.path("lat").asText()),
                        Double.parseDouble(item.path("lon").asText()),
                        item.path("type").asText(""),
                        item.path("osm_type").asText(""),
                        item.path("osm_id").asLong(0)));
            } catch (RuntimeException ignored) {
                // Invalid coordinates from a provider result are skipped.
            }
        }
        List<Result> immutable = List.copyOf(results);
        cache.put(key, immutable);
        return immutable;
    }

    private static URI defaultEndpoint() {
        String configured = System.getProperty(
                "bred.map.nominatimUrl",
                "https://nominatim.openstreetmap.org/search");
        try {
            return URI.create(configured);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Configured Nominatim endpoint is invalid.", invalid);
        }
    }
}
