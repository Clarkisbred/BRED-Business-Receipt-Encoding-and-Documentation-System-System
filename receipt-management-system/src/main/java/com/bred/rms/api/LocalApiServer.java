package com.bred.rms.api;

import com.bred.rms.config.AppContext;
import com.bred.rms.models.Project;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.User;
import com.bred.rms.security.Module;
import com.bred.rms.services.AuthException;
import com.bred.rms.ui.theme.ThemeManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Authenticated loopback API for a trusted embedded desktop renderer. */
public final class LocalApiServer implements AutoCloseable {
    public record RendererBootstrap(String origin, String token) { }
    public record UserProfile(String id, String fullName, String email, String role, String companyName) { }
    public record SessionResponse(UserProfile user) { }
    public record ProjectSummary(String id, String name, String code, int receiptCount, String spend) { }
    public record MonthlyMetric(String month, String revenue, String expenses, String profit, String cashFlow) { }
    public record ExpenseMetric(String name, String amount) { }
    public record ProjectMetric(String id, String name, String budget, String actual, String progress) { }
    public record DashboardData(String totalSpend, String monthSpend, int monthReceiptCount,
                                int totalReceiptCount, int projectCount, List<ProjectSummary> projects,
                                String totalRevenue, String totalExpenses, String netProfit,
                                String outstandingPayables, String outstandingReceivables, int supplierCount,
                                List<MonthlyMetric> monthly, List<ExpenseMetric> expenseBreakdown,
                                List<ProjectMetric> projectMetrics, boolean cashFlowAvailable) {
        public DashboardData(String totalSpend, String monthSpend, int monthReceiptCount,
                             int totalReceiptCount, int projectCount, List<ProjectSummary> projects) {
            this(totalSpend, monthSpend, monthReceiptCount, totalReceiptCount, projectCount, projects,
                    "0.00", totalSpend, "0.00", "0.00", "0.00", 0,
                    List.of(), List.of(), List.of(), false);
        }
    }
    public record ModuleOption(String label, String value, String description, String taxClassification) {
        public ModuleOption(String label, String value, String description) {
            this(label, value, description, "");
        }
        public ModuleOption(String label, String value) { this(label, value, "", ""); }
    }
    public record ModuleField(String key, String label, String type, boolean required,
                              List<ModuleOption> options, Object value) { }
    public record ModuleColumn(String key, String label, boolean numeric) { }
    public record ModuleMetricPoint(String label, java.math.BigDecimal value) { }
    public record ModuleMetric(String label, String value, String note, List<ModuleMetricPoint> chart) {
        public ModuleMetric {
            chart = chart == null ? List.of() : List.copyOf(chart);
        }

        public ModuleMetric(String label, String value, String note) {
            this(label, value, note, List.of());
        }
    }
    public record ModuleRecord(String id, Map<String, Object> values) { }
    public record ConstructionTabInfo(String title, String table) { }
    public record ConstructionCategory(String title, List<ConstructionTabInfo> tabs) { }
    public record ModulePageData(String key, String title, String description, List<ModuleMetric> metrics,
                                 List<ModuleColumn> columns, List<ModuleField> fields, List<ModuleRecord> rows,
                                 boolean canCreate, boolean canEdit, boolean canDelete,
                                 String primaryActionLabel, String submitLabel, String createAction,
                                 List<ConstructionCategory> constructionCategories,
                                 List<Map<String, Object>> billingConfigurations,
                                 List<Map<String, Object>> billingRetentionReleases,
                                 List<Map<String, Object>> billingLegacyRetention,
                                 List<Map<String, Object>> billingMobilizationBalances,
                                 Map<String, Object> royaltyConfiguration,
                                 List<Map<String, Object>> royaltyContracts,
                                 List<Map<String, Object>> royaltySuppliers,
                                 List<Map<String, Object>> royaltyReturns,
                                 List<Map<String, Object>> royaltyEvents) {
        public ModulePageData(String key, String title, String description, List<ModuleMetric> metrics,
                              List<ModuleColumn> columns, List<ModuleField> fields, List<ModuleRecord> rows,
                              boolean canCreate, boolean canEdit, boolean canDelete,
                              String primaryActionLabel, String submitLabel, String createAction,
                              List<ConstructionCategory> constructionCategories,
                              List<Map<String, Object>> billingConfigurations,
                              List<Map<String, Object>> billingRetentionReleases,
                              List<Map<String, Object>> billingLegacyRetention,
                              List<Map<String, Object>> billingMobilizationBalances) {
            this(key, title, description, metrics, columns, fields, rows, canCreate, canEdit, canDelete,
                    primaryActionLabel, submitLabel, createAction, constructionCategories,
                    billingConfigurations, billingRetentionReleases, billingLegacyRetention,
                    billingMobilizationBalances, Map.of(), List.of(), List.of(), List.of(), List.of());
        }

        public ModulePageData(String key, String title, String description, List<ModuleMetric> metrics,
                              List<ModuleColumn> columns, List<ModuleField> fields, List<ModuleRecord> rows,
                              boolean canCreate, boolean canEdit, boolean canDelete,
                              String primaryActionLabel, String submitLabel, String createAction,
                              List<ConstructionCategory> constructionCategories,
                              List<Map<String, Object>> billingConfigurations,
                              List<Map<String, Object>> billingRetentionReleases) {
            this(key, title, description, metrics, columns, fields, rows, canCreate, canEdit, canDelete,
                    primaryActionLabel, submitLabel, createAction, constructionCategories,
                    billingConfigurations, billingRetentionReleases, List.of(), List.of(),
                    Map.of(), List.of(), List.of(), List.of(), List.of());
        }

        public ModulePageData(String key, String title, String description, List<ModuleMetric> metrics,
                              List<ModuleColumn> columns, List<ModuleField> fields, List<ModuleRecord> rows,
                              boolean canCreate, boolean canEdit, boolean canDelete,
                              String primaryActionLabel, String submitLabel, String createAction,
                              List<ConstructionCategory> constructionCategories) {
            this(key, title, description, metrics, columns, fields, rows, canCreate, canEdit, canDelete,
                    primaryActionLabel, submitLabel, createAction, constructionCategories,
                    List.of(), List.of(), List.of(), List.of(),
                    Map.of(), List.of(), List.of(), List.of(), List.of());
        }

        public ModulePageData(String key, String title, String description, List<ModuleMetric> metrics,
                              List<ModuleColumn> columns, List<ModuleField> fields, List<ModuleRecord> rows,
                              boolean canCreate, boolean canEdit, boolean canDelete,
                              String primaryActionLabel, String submitLabel, String createAction) {
            this(key, title, description, metrics, columns, fields, rows, canCreate, canEdit, canDelete,
                    primaryActionLabel, submitLabel, createAction, List.of());
        }
    }
    public record MapSearchResult(String displayName, String latitude, String longitude, String type) { }
    public record ThemeData(String appearance, boolean dark, String accent, String accentDark, String accentSoft,
                            String background, String sidebar, String header, String surface, String surfaceMuted,
                            String text, String muted, String border, String success, String warning, String danger,
                            String chart1, String chart2, String chart3, String chart4, String chart5) {
        static ThemeData from(ThemeManager.ReactTheme theme) {
            return new ThemeData(theme.appearance(), theme.dark(), theme.accent(), theme.accentDark(),
                    theme.accentSoft(), theme.background(), theme.sidebar(), theme.header(), theme.surface(),
                    theme.surfaceMuted(), theme.text(), theme.muted(), theme.border(), theme.success(),
                    theme.warning(), theme.danger(), theme.chart1(), theme.chart2(), theme.chart3(),
                    theme.chart4(), theme.chart5());
        }
    }
    private record ApiError(String code, String message) { }
    private record RequestFailure(int status, String code, String message) { }

    private static final Logger LOGGER = Logger.getLogger(LocalApiServer.class.getName());
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_RECEIPT_REQUEST_BYTES = 15 * 1024 * 1024;
    private static final int MAX_STATIC_RESOURCE_BYTES = 10 * 1024 * 1024;
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HttpServer server;
    private final ExecutorService executor;
    private final LocalApiBackend backend;
    private final Object sessionLock = new Object();
    private final byte[] tokenBytes;
    private final String origin;
    private final String expectedHost;
    private volatile boolean closed;

    private LocalApiServer(LocalApiBackend backend) throws IOException {
        this.backend = backend;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        server = HttpServer.create(new InetSocketAddress(loopback, 0), 16);
        expectedHost = "127.0.0.1:" + server.getAddress().getPort();
        origin = "http://" + expectedHost;
        byte[] secret = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(secret);
        tokenBytes = secret;
        executor = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "bred-local-api");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/api/v1", this::handle);
        server.createContext("/", this::handleStatic);
        server.start();
    }

    /** Starts the API over the existing authenticated BRED services. */
    public static LocalApiServer start(AppContext context) throws IOException {
        if (context == null) throw new IllegalArgumentException("Application context is required.");
        return new LocalApiServer(new ContextBackend(context));
    }

    /** Starts an API instance for same-package tests. */
    static LocalApiServer startForTesting(LocalApiBackend backend) throws IOException {
        return new LocalApiServer(backend);
    }

    /**
     * Supplies the launch token to the native host only. The host must inject it into the trusted top-level
     * renderer through its native bridge; never place it in a URL, static HTML, environment variable, or HTTP route.
     */
    public synchronized RendererBootstrap rendererBootstrap() {
        if (closed) throw new IllegalStateException("The BRED local API is closed.");
        return new RendererBootstrap(origin, Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes));
    }

    /** Ends the shared desktop session before the legacy Swing sign-in screen is shown. */
    public void signOutRendererSession() {
        synchronized (sessionLock) {
            if (closed) throw new IllegalStateException("The BRED local API is closed.");
            backend.logout();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            setResponseHeaders(exchange);
            if (!isTrustedRequest(exchange)) {
                sendError(exchange, 403, "forbidden", "This request is not from the BRED desktop renderer.");
                return;
            }
            if (!hasValidToken(exchange)) {
                sendError(exchange, 401, "unauthorized", "The BRED desktop session is unavailable.");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/api/v1/session".equals(path)) {
                handleSession(exchange, method);
            } else if ("/api/v1/dashboard".equals(path)) {
                if (!"GET".equals(method)) {
                    methodNotAllowed(exchange, "GET");
                } else {
                    handleDashboard(exchange);
                }
            } else if ("/api/v1/theme".equals(path)) {
                if (!"GET".equals(method)) {
                    methodNotAllowed(exchange, "GET");
                } else {
                    sendJson(exchange, 200, backend.theme());
                }
            } else if ("/api/v1/map/search".equals(path)) {
                handleMapSearch(exchange, method);
            } else if (path.startsWith("/api/v1/modules/")) {
                handleModule(exchange, path, method);
            } else {
                sendError(exchange, 404, "not_found", "The requested BRED API endpoint was not found.");
            }
        } catch (RequestException error) {
            sendError(exchange, error.failure.status(), error.failure.code(), error.failure.message());
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            LOGGER.log(Level.WARNING, "The local BRED API request failed.", error);
            sendError(exchange, 500, "internal_error", "BRED could not complete this request.");
        } finally {
            exchange.close();
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        try {
            setResponseHeaders(exchange);
            if (!isLoopbackPeer(exchange)
                    || !expectedHost.equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Host"))) {
                sendError(exchange, 403, "forbidden", "This request is not from the BRED desktop host.");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                methodNotAllowed(exchange, "GET, HEAD");
                return;
            }

            String requestPath = exchange.getRequestURI().getPath();
            if (!isSafeStaticPath(requestPath)) {
                sendError(exchange, 404, "not_found", "The requested BRED resource was not found.");
                return;
            }
            String resourcePath = "/web-app" + ("/".equals(requestPath) ? "/index.html" : requestPath);
            byte[] content;
            try (InputStream resource = LocalApiServer.class.getResourceAsStream(resourcePath)) {
                if (resource == null) {
                    sendError(exchange, 404, "not_found", "The requested BRED resource was not found.");
                    return;
                }
                content = resource.readNBytes(MAX_STATIC_RESOURCE_BYTES + 1);
            }
            if (content.length > MAX_STATIC_RESOURCE_BYTES) {
                sendError(exchange, 500, "resource_too_large", "The BRED web resource could not be served.");
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", contentType(resourcePath));
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data: https://tile.openstreetmap.org; "
                            + "font-src 'self' data:; connect-src 'self'; "
                            + "object-src 'none'; frame-src https://www.openstreetmap.org; "
                            + "frame-ancestors 'none'; base-uri 'none'");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin");
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            }
        } finally {
            exchange.close();
        }
    }

    private static boolean isSafeStaticPath(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static String contentType(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".html")) return "text/html; charset=utf-8";
        if (lowerPath.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lowerPath.endsWith(".css")) return "text/css; charset=utf-8";
        if (lowerPath.endsWith(".svg")) return "image/svg+xml";
        if (lowerPath.endsWith(".png")) return "image/png";
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) return "image/jpeg";
        if (lowerPath.endsWith(".webp")) return "image/webp";
        if (lowerPath.endsWith(".woff2")) return "font/woff2";
        if (lowerPath.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private void handleSession(HttpExchange exchange, String method) throws IOException, RequestException {
        switch (method) {
            case "POST" -> signIn(exchange);
            case "GET" -> currentSession(exchange);
            case "DELETE" -> signOut(exchange);
            default -> methodNotAllowed(exchange, "GET, POST, DELETE");
        }
    }

    private void signIn(HttpExchange exchange) throws IOException, RequestException {
        JsonNode request = readJson(exchange);
        if (!request.path("email").isTextual() || !request.path("password").isTextual()) {
            sendError(exchange, 400, "invalid_request", "Enter your email and password.");
            return;
        }
        String email = request.path("email").asText().trim();
        String passwordValue = request.path("password").asText();
        if (email.isBlank() || email.length() > 254 || passwordValue.isEmpty() || passwordValue.length() > 1024) {
            sendError(exchange, 400, "invalid_request", "Enter a valid email address and password.");
            return;
        }
        char[] password = passwordValue.toCharArray();
        try {
            User user;
            synchronized (sessionLock) {
                user = backend.login(email, password);
            }
            sendJson(exchange, 200, new SessionResponse(profile(user)));
        } catch (AuthException invalidCredentials) {
            sendError(exchange, 401, "sign_in_failed", invalidCredentials.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void currentSession(HttpExchange exchange) throws IOException {
        User user;
        synchronized (sessionLock) {
            user = backend.current();
        }
        if (user == null) {
            sendError(exchange, 401, "signed_out", "Sign in to continue.");
            return;
        }
        sendJson(exchange, 200, new SessionResponse(profile(user)));
    }

    private void signOut(HttpExchange exchange) throws IOException {
        synchronized (sessionLock) {
            backend.logout();
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        synchronized (sessionLock) {
            if (backend.current() == null) {
                sendError(exchange, 401, "signed_out", "Sign in to continue.");
                return;
            }
            sendJson(exchange, 200, backend.dashboard());
        }
    }

    private void handleModule(HttpExchange exchange, String path, String method)
            throws IOException, RequestException {
        String key = path.substring("/api/v1/modules/".length());
        if (key.isBlank() || key.contains("/")) {
            sendError(exchange, 404, "not_found", "The requested BRED workspace was not found.");
            return;
        }
        Module module;
        try {
            module = Module.valueOf(key);
        } catch (IllegalArgumentException invalidModule) {
            sendError(exchange, 404, "not_found", "The requested BRED workspace was not found.");
            return;
        }
        if (module == Module.DASHBOARD) {
            sendError(exchange, 404, "not_found", "The dashboard uses its dedicated data endpoint.");
            return;
        }
        String table = queryParameter(exchange.getRequestURI().getRawQuery(), "table");
        if (table != null && module != Module.CONSTRUCTION) {
            sendError(exchange, 400, "invalid_request", "A construction table can only be loaded in Construction.");
            return;
        }
        synchronized (sessionLock) {
            User user = backend.current();
            if (user == null) {
                sendError(exchange, 401, "signed_out", "Sign in to continue.");
                return;
            }
            if (!module.allows(user.role())) {
                sendError(exchange, 403, "forbidden", "Your BRED role cannot open this workspace.");
                return;
            }
            try {
                if ("GET".equals(method)) {
                    sendJson(exchange, 200, backend.modulePage(module, table));
                } else if ("POST".equals(method)) {
                    JsonNode request = readJson(exchange, module == Module.RECEIPTS || module == Module.CONSTRUCTION
                            ? MAX_RECEIPT_REQUEST_BYTES : MAX_REQUEST_BYTES);
                    String action = request.path("action").asText("");
                    String id = request.path("id").isTextual() ? request.path("id").asText() : null;
                    sendJson(exchange, 200, backend.moduleAction(module, action, id, request.get("values")));
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
            } catch (SecurityException denied) {
                sendError(exchange, 403, "forbidden", denied.getMessage());
            } catch (IllegalArgumentException | com.bred.rms.models.ValidationException invalid) {
                sendError(exchange, 400, "invalid_request", invalid.getMessage());
            }
        }
    }

    private void handleMapSearch(HttpExchange exchange, String method) throws IOException, RequestException {
        if (!"GET".equals(method)) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        User user;
        synchronized (sessionLock) {
            user = backend.current();
        }
        if (user == null) {
            sendError(exchange, 401, "signed_out", "Sign in to continue.");
            return;
        }
        if (!Module.LIVE_MAP.allows(user.role())) {
            sendError(exchange, 403, "forbidden", "Your BRED role cannot open the project map.");
            return;
        }
        String query = queryParameter(exchange.getRequestURI().getRawQuery(), "q");
        if (query == null || query.isBlank() || query.length() > 160) {
            throw new RequestException(400, "invalid_request", "Enter a place or address to search.");
        }
        try {
            sendJson(exchange, 200, backend.mapSearch(query));
        } catch (IllegalArgumentException invalid) {
            throw new RequestException(400, "invalid_request", invalid.getMessage());
        } catch (SecurityException denied) {
            sendError(exchange, 403, "forbidden", denied.getMessage());
        }
    }

    private static String queryParameter(String rawQuery, String key) throws RequestException {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            try {
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (key.equals(name)) {
                    return pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException malformed) {
                throw new RequestException(400, "invalid_request", "The location search query is invalid.");
            }
        }
        return null;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException, RequestException {
        return readJson(exchange, MAX_REQUEST_BYTES);
    }

    private JsonNode readJson(HttpExchange exchange, int maxBytes) throws IOException, RequestException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!"application/json".equalsIgnoreCase(mediaType)) {
            throw new RequestException(415, "unsupported_media_type", "Send the request as JSON.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(maxBytes + 1);
        try {
            if (body.length > maxBytes) {
                throw new RequestException(413, "request_too_large", "The request body is too large.");
            }
            JsonNode parsed = JSON.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw new RequestException(400, "invalid_request", "The request body must be a JSON object.");
            }
            return parsed;
        } catch (IOException malformed) {
            throw new RequestException(400, "invalid_request", "The request body is not valid JSON.");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private boolean isTrustedRequest(HttpExchange exchange) {
        if (!isLoopbackPeer(exchange)) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (!expectedHost.equalsIgnoreCase(host)) return false;
        if (requestOrigin != null) return origin.equals(requestOrigin);
        return "same-origin".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"));
    }

    private static boolean isLoopbackPeer(HttpExchange exchange) {
        return exchange.getRemoteAddress() instanceof InetSocketAddress remote
                && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
    }

    private boolean hasValidToken(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() > TOKEN_BYTES * 2) {
            return false;
        }
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(authorization.substring("Bearer ".length()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        try {
            return MessageDigest.isEqual(tokenBytes, supplied);
        } finally {
            Arrays.fill(supplied, (byte) 0);
        }
    }

    private static UserProfile profile(User user) {
        return new UserProfile(user.id().toString(), user.fullName(), user.email(),
                user.role().name().toLowerCase(Locale.ROOT), user.companyName());
    }

    private static void setResponseHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendError(exchange, 405, "method_not_allowed", "This method is not supported for the endpoint.");
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, new ApiError(code, message == null ? "The request could not be completed." : message));
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class RequestException extends Exception {
        private final RequestFailure failure;

        private RequestException(int status, String code, String message) {
            this.failure = new RequestFailure(status, code, message);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        server.stop(0);
        executor.shutdownNow();
        Arrays.fill(tokenBytes, (byte) 0);
    }

    private static final class ContextBackend implements LocalApiBackend {
        private final AppContext context;
        private final ReactWorkspaceService workspaces;

        private ContextBackend(AppContext context) {
            this.context = context;
            this.workspaces = new ReactWorkspaceService(context);
        }

        @Override public User login(String email, char[] password) throws AuthException {
            return context.auth.login(email, password);
        }

        @Override public User current() {
            return context.currentUser.get();
        }

        @Override public void logout() {
            context.auth.logout();
        }

        @Override public DashboardData dashboard() {
            List<Receipt> receipts = context.reports.receipts(null, null);
            List<Project> projects = context.projects.findAll();
            List<JsonNode> billings = context.constructionData.rows("project_billings");
            List<JsonNode> boqItems = context.constructionData.rows("project_boq_items");
            List<JsonNode> payables = context.constructionData.rows("project_payables");
            List<JsonNode> collections = context.constructionData.rows("project_billing_collections");
            List<JsonNode> payments = context.constructionData.rows("project_payments");
            return DashboardAnalytics.build(receipts, projects, context.suppliers.findAll().size(),
                    billings, boqItems, payables, collections, payments, YearMonth.now());
        }

        @Override public ModulePageData modulePage(Module module) {
            return workspaces.load(module);
        }

        @Override public ModulePageData modulePage(Module module, String table) {
            return workspaces.load(module, table);
        }

        @Override public ModulePageData moduleAction(Module module, String action, String id, JsonNode values) {
            return workspaces.apply(module, action, id, values);
        }

        @Override public List<MapSearchResult> mapSearch(String query) {
            return workspaces.searchMap(query);
        }
    }
}
