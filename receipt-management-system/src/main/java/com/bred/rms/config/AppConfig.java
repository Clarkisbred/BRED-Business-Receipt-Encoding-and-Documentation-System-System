package com.bred.rms.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Properties;

/** Loads public Supabase settings. Precedence: environment variables, user config, legacy config, classpath. */
public final class AppConfig {
    private final String supabaseUrl;
    private final String anonKey;

    /** Creates an app config instance. */
    private AppConfig(String supabaseUrl, String anonKey) {
        this.supabaseUrl = supabaseUrl;
        this.anonKey = anonKey;
    }

    /** Loads Supabase settings from environment variables, a local properties file, or the classpath. */
    public static AppConfig load() {
        Properties props;
        try {
            Path userConfig = userPropertiesPath();
            if (Files.isRegularFile(userConfig)) {
                props = readProperties(userConfig);
            } else {
                Path legacyConfig = findLegacyProperties();
                if (legacyConfig != null) {
                    props = loadProperties(userConfig, legacyConfig, null);
                } else {
                    try (InputStream bundled = AppConfig.class.getResourceAsStream("/application.properties")) {
                        props = loadProperties(userConfig, null, bundled);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read application.properties: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("application.properties contains invalid configuration.", e);
        }
        String url = firstNonBlank(System.getenv("RMS_SUPABASE_URL"), props.getProperty("supabase.url"));
        String key = firstNonBlank(System.getenv("RMS_SUPABASE_ANON_KEY"), props.getProperty("supabase.anonKey"));
        if (isPrivilegedKey(key)) {
            throw new IllegalStateException("A privileged cloud database key was supplied. Use only the public application key in the desktop app.");
        }
        return new AppConfig(url.replaceAll("/+$", ""), key);
    }

    private static Path findLegacyProperties() throws IOException {
        Path workingDirectoryFile = Path.of("application.properties").toAbsolutePath().normalize();
        if (Files.isRegularFile(workingDirectoryFile)) return workingDirectoryFile;
        try {
            Path location = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = Files.isDirectory(location) ? location : location.getParent();
            for (int i = 0; directory != null && i < 4; i++, directory = directory.getParent()) {
                Path candidate = directory.resolve("application.properties");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        } catch (URISyntaxException | SecurityException e) {
            throw new IOException("Could not locate application.properties beside the application.", e);
        }
        return null;
    }

    private static Path userPropertiesPath() {
        String appData = System.getenv("APPDATA");
        Path root = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming") : Path.of(appData);
        return root.resolve("BRED").resolve("application.properties");
    }

    static Properties loadProperties(Path userConfig, Path legacyConfig, InputStream bundled) throws IOException {
        if (Files.isRegularFile(userConfig)) return readProperties(userConfig);
        if (legacyConfig != null && Files.isRegularFile(legacyConfig)
                && !legacyConfig.toAbsolutePath().normalize().equals(userConfig.toAbsolutePath().normalize())) {
            migrateProperties(legacyConfig, userConfig);
            return readProperties(userConfig);
        }
        Properties props = new Properties();
        if (bundled != null) {
            try (InputStream in = bundled) {
                props.load(in);
            }
        }
        return props;
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        return props;
    }

    private static void migrateProperties(Path source, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "application-", ".properties.tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            readProperties(temporary);
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException raced) {
                // Another process already migrated the configuration; keep and use its copy.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Builds a config directly from values (used by tests and tools). Rejects privileged keys. */
    public static AppConfig of(String supabaseUrl, String anonKey) {
        if (isPrivilegedKey(anonKey)) {
            throw new IllegalStateException("A privileged cloud database key was supplied. Use only the public application key in the desktop app.");
        }
        return new AppConfig(supabaseUrl.replaceAll("/+$", ""), anonKey);
    }

    /** Reports whether this app config is configured. */
    public boolean isConfigured() {
        return !supabaseUrl.isBlank() && !anonKey.isBlank() && !supabaseUrl.contains("YOUR-PROJECT-REF");
    }

    /** Returns the configured Supabase project URL. */
    public String supabaseUrl() { return supabaseUrl; }
    /** Returns the configured public Supabase anon key. */
    public String anonKey() { return anonKey; }

    /** Rejects service-role JWTs and new-style secret keys. */
    public static boolean isPrivilegedKey(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith("sb_secret_")) return true;
        String[] parts = key.split("\\.");
        if (parts.length == 3) {
            try {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                return payload.contains("\"service_role\"");
            } catch (IllegalArgumentException ignored) { /* not a JWT */ }
        }
        return false;
    }

    /** Returns the first non-blank trimmed value, or an empty string when both are blank. */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null ? "" : b.trim();
    }
}
