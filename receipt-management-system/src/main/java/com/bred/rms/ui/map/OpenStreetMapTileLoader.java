package com.bred.rms.ui.map;

import com.bred.rms.config.AppVersion;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads only tiles needed by the current map viewport and honors HTTP cache validators. */
final class OpenStreetMapTileLoader {
    record Result(WebMercator.Tile tile, BufferedImage image, String error) { }
    private record CachedTile(byte[] bytes, String etag, String lastModified, Instant expiresAt) { }

    private static final Logger LOGGER = Logger.getLogger(OpenStreetMapTileLoader.class.getName());
    private static final long DEFAULT_CACHE_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final long MAX_TILE_BYTES = 1_048_576;
    private static final int MEMORY_CACHE_SIZE = 128;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*\"?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final OpenStreetMapTileLoader SHARED = new OpenStreetMapTileLoader();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "bred-map-tiles");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<WebMercator.Tile, BufferedImage> memory = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMORY_CACHE_SIZE, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<WebMercator.Tile, BufferedImage> eldest) {
                    return size() > MEMORY_CACHE_SIZE;
                }
            });
    private final ConcurrentMap<WebMercator.Tile, CopyOnWriteArrayList<Consumer<Result>>> inFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<WebMercator.Tile, Instant> retryAfter =
            new ConcurrentHashMap<>();

    private OpenStreetMapTileLoader() { }

    static OpenStreetMapTileLoader shared() {
        return SHARED;
    }

    BufferedImage cached(WebMercator.Tile tile) {
        return memory.get(tile);
    }

    boolean failedRecently(WebMercator.Tile tile) {
        Instant retry = retryAfter.get(tile);
        return retry != null && retry.isAfter(Instant.now());
    }

    void retryFailed() {
        retryAfter.clear();
    }

    void load(WebMercator.Tile tile, Consumer<Result> completion) {
        if (memory.containsKey(tile) || failedRecently(tile)) return;
        CopyOnWriteArrayList<Consumer<Result>> listeners = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Consumer<Result>> existing = inFlight.putIfAbsent(tile, listeners);
        if (existing != null) {
            existing.add(completion);
            return;
        }
        listeners.add(completion);
        executor.execute(() -> {
            Result result;
            try {
                BufferedImage image = loadTile(tile);
                memory.put(tile, image);
                result = new Result(tile, image, null);
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                LOGGER.log(Level.FINE, "Could not load an OpenStreetMap tile.", error);
                retryAfter.put(tile, Instant.now().plus(RETRY_DELAY));
                result = new Result(tile, null, error.getMessage());
            } finally {
                inFlight.remove(tile, listeners);
            }
            Result completed = result;
            SwingUtilities.invokeLater(() -> listeners.forEach(listener -> listener.accept(completed)));
        });
    }

    private BufferedImage loadTile(WebMercator.Tile tile) throws IOException, InterruptedException {
        Path cache = cachePath(tile);
        CachedTile cached = readCache(cache);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) return decode(cached.bytes());

        HttpRequest.Builder request = HttpRequest.newBuilder(tileUri(tile))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "BRED/" + AppVersion.current() + " (Windows desktop map)")
                .header("Accept", "image/png");
        if (cached != null) {
            if (!cached.etag().isBlank()) request.header("If-None-Match", cached.etag());
            if (!cached.lastModified().isBlank()) request.header("If-Modified-Since", cached.lastModified());
        }
        HttpResponse<byte[]> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 304 && cached != null) {
            String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
            if (cacheControl.toLowerCase(Locale.ROOT).contains("no-store")) {
                Files.deleteIfExists(cache);
                Files.deleteIfExists(metadataPath(cache));
                return decode(cached.bytes());
            }
            Instant expires = expiration(response.headers(), now);
            CachedTile refreshed = new CachedTile(cached.bytes(),
                    response.headers().firstValue("ETag").orElse(cached.etag()),
                    response.headers().firstValue("Last-Modified").orElse(cached.lastModified()), expires);
            saveCache(cache, refreshed);
            return decode(refreshed.bytes());
        }
        if (response.statusCode() != 200) {
            throw new IOException("Tile server returned HTTP " + response.statusCode());
        }
        byte[] bytes = response.body();
        if (bytes.length == 0 || bytes.length > MAX_TILE_BYTES) {
            throw new IOException("Tile server returned an invalid image size.");
        }
        BufferedImage image = decode(bytes);
        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        if (cacheControl.toLowerCase(Locale.ROOT).contains("no-store")) {
            Files.deleteIfExists(cache);
            Files.deleteIfExists(metadataPath(cache));
        } else {
            saveCache(cache, new CachedTile(bytes,
                    response.headers().firstValue("ETag").orElse(""),
                    response.headers().firstValue("Last-Modified").orElse(""),
                    expiration(response.headers(), now)));
        }
        return image;
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != WebMercator.TILE_SIZE
                || image.getHeight() != WebMercator.TILE_SIZE) {
            throw new IOException("Tile server returned an unsupported image.");
        }
        return image;
    }

    private static URI tileUri(WebMercator.Tile tile) throws IOException {
        String template = System.getProperty("bred.map.tileUrl",
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
        if (!template.contains("{z}") || !template.contains("{x}") || !template.contains("{y}")) {
            throw new IOException("The map tile URL must contain {z}, {x}, and {y}.");
        }
        String url = template.replace("{z}", Integer.toString(tile.zoom()))
                .replace("{x}", Integer.toString(tile.x()))
                .replace("{y}", Integer.toString(tile.y()));
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IOException("The map tile URL must use HTTPS.");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The configured map tile URL is invalid.", invalid);
        }
    }

    private static Instant expiration(HttpHeaders headers, Instant now) {
        String cacheControl = headers.firstValue("Cache-Control").orElse("");
        if (cacheControl.toLowerCase(Locale.ROOT).contains("no-cache")) return now;
        Matcher maxAge = MAX_AGE.matcher(cacheControl);
        if (maxAge.find()) {
            try {
                return now.plusSeconds(Long.parseLong(maxAge.group(1)));
            } catch (RuntimeException invalid) {
                LOGGER.log(Level.FINE, "Ignoring invalid max-age on a map tile response.", invalid);
            }
        }
        String expires = headers.firstValue("Expires").orElse("");
        if (!expires.isBlank()) {
            try {
                return DateTimeFormatter.RFC_1123_DATE_TIME.parse(expires, Instant::from);
            } catch (DateTimeParseException invalid) {
                LOGGER.log(Level.FINE, "Ignoring invalid Expires header on a map tile response.", invalid);
            }
        }
        return now.plusSeconds(DEFAULT_CACHE_SECONDS);
    }

    private static CachedTile readCache(Path path) throws IOException {
        Path metadata = metadataPath(path);
        if (!Files.isRegularFile(path) || !Files.isRegularFile(metadata)
                || Files.size(path) > MAX_TILE_BYTES) return null;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(metadata)) {
            properties.load(input);
        }
        try {
            long expiry = Long.parseLong(properties.getProperty("expiresAt", "0"));
            byte[] bytes = Files.readAllBytes(path);
            decode(bytes);
            return new CachedTile(bytes, properties.getProperty("etag", ""),
                    properties.getProperty("lastModified", ""), Instant.ofEpochMilli(expiry));
        } catch (NumberFormatException | IOException invalid) {
            LOGGER.log(Level.WARNING, "Discarding an invalid cached OpenStreetMap tile.", invalid);
            Files.deleteIfExists(path);
            Files.deleteIfExists(metadata);
            return null;
        }
    }

    private static void saveCache(Path path, CachedTile tile) {
        try {
            Files.createDirectories(path.getParent());
            Path imageTemp = Files.createTempFile(path.getParent(), "tile-", ".tmp");
            Path metadata = metadataPath(path);
            Path metadataTemp = Files.createTempFile(path.getParent(), "tile-meta-", ".tmp");
            try {
                Files.write(imageTemp, tile.bytes());
                Properties properties = new Properties();
                properties.setProperty("expiresAt", Long.toString(tile.expiresAt().toEpochMilli()));
                properties.setProperty("etag", tile.etag());
                properties.setProperty("lastModified", tile.lastModified());
                try (var output = Files.newOutputStream(metadataTemp)) {
                    properties.store(output, "BRED OpenStreetMap tile cache");
                }
                moveReplacing(imageTemp, path);
                moveReplacing(metadataTemp, metadata);
            } finally {
                Files.deleteIfExists(imageTemp);
                Files.deleteIfExists(metadataTemp);
            }
        } catch (IOException error) {
            LOGGER.log(Level.WARNING, "Could not persist an OpenStreetMap tile cache entry.", error);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path cachePath(WebMercator.Tile tile) {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local", "BRED", "map-tiles")
                : Path.of(localAppData, "BRED", "map-tiles");
        return root.resolve(tile.zoom() + "-" + tile.x() + "-" + tile.y() + ".png");
    }

    private static Path metadataPath(Path path) {
        return path.resolveSibling(path.getFileName().toString().replaceFirst("\\.png$", ".properties"));
    }
}
