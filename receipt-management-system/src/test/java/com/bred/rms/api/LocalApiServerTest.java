package com.bred.rms.api;

import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.services.AuthException;
import com.bred.rms.security.Module;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalApiServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void authenticatesAndServesJavaDashboardDataOnlyForTheTrustedRenderer() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());

            HttpResponse<String> signedOut = send(apiRequest(origin, bootstrap.token()).GET().build());
            assertEquals(401, signedOut.statusCode());

            HttpRequest signIn = apiRequest(origin, bootstrap.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\" user@example.test \",\"password\":\"password123\"}"))
                    .build();
            HttpResponse<String> authenticated = send(signIn);
            assertEquals(200, authenticated.statusCode());
            assertEquals("admin", JSON.readTree(authenticated.body()).path("user").path("role").asText());
            assertNotNull(backend.passwordReference);
            for (char value : backend.passwordReference) assertEquals('\0', value);

            HttpResponse<String> current = send(apiRequest(origin, bootstrap.token()).GET().build());
            assertEquals(200, current.statusCode());
            assertEquals("Test Company", JSON.readTree(current.body()).path("user").path("companyName").asText());

            HttpResponse<String> dashboard = send(apiRequest(origin, bootstrap.token(), "/api/v1/dashboard")
                    .GET().build());
            assertEquals(200, dashboard.statusCode());
            JsonNode dashboardJson = JSON.readTree(dashboard.body());
            assertEquals("12.34", dashboardJson.path("totalSpend").asText());
            assertEquals("Test Project", dashboardJson.path("projects").get(0).path("name").asText());
            assertTrue(dashboardJson.path("monthly").isArray());
            assertTrue(dashboardJson.path("expenseBreakdown").isArray());
            assertTrue(dashboardJson.path("projectMetrics").isArray());
            assertTrue(dashboardJson.has("cashFlowAvailable"));
            assertEquals("no-store", dashboard.headers().firstValue("Cache-Control").orElse(""));
            assertFalse(dashboard.headers().firstValue("Access-Control-Allow-Origin").isPresent());

            HttpResponse<String> signedOutResponse = send(apiRequest(origin, bootstrap.token())
                    .DELETE().build());
            assertEquals(204, signedOutResponse.statusCode());
            assertNull(backend.current());
        }
    }

    @Test
    void servesJavaThemeTokensToTheTrustedRenderer() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            HttpResponse<String> theme = send(apiRequest(URI.create(bootstrap.origin()),
                    bootstrap.token(), "/api/v1/theme").GET().build());
            assertEquals(200, theme.statusCode());
            JsonNode themeJson = JSON.readTree(theme.body());
            assertFalse(themeJson.path("appearance").asText().isBlank());
            assertFalse(themeJson.path("background").asText().isBlank());
            assertTrue(themeJson.path("chart1").asText().startsWith("#"));
        }
    }

    @Test
    void servesWorkspaceDataAndEnforcesModuleRoles() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = backend.user;
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());

            HttpResponse<String> projects = send(apiRequest(origin, bootstrap.token(), "/api/v1/modules/PROJECTS")
                    .GET().build());
            assertEquals(200, projects.statusCode());
            assertEquals("Projects", JSON.readTree(projects.body()).path("title").asText());
            assertEquals("1970-01-01T00:00:00Z", JSON.readTree(projects.body()).path("rows").get(0)
                    .path("values").path("createdAt").asText());
            assertEquals(1, backend.modulePageCalls);

            HttpResponse<String> companyAdminDenied = send(apiRequest(origin, bootstrap.token(),
                    "/api/v1/modules/COMPANIES").GET().build());
            assertEquals(403, companyAdminDenied.statusCode());
            assertEquals(1, backend.modulePageCalls);

            HttpResponse<String> invalidModule = send(apiRequest(origin, bootstrap.token(),
                    "/api/v1/modules/NOT_A_MODULE").GET().build());
            assertEquals(404, invalidModule.statusCode());
        }
    }

    @Test
    void validatesWorkspaceActionsAndUsesServerSideModuleService() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = backend.user;
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());
            HttpResponse<String> updated = send(apiRequest(origin, bootstrap.token(), "/api/v1/modules/PROJECTS")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"action\":\"save\",\"values\":{\"name\":\"New project\"}}"))
                    .build());
            assertEquals(200, updated.statusCode());
            assertEquals("save", backend.lastModuleAction);

            HttpResponse<String> invalidAction = send(apiRequest(origin, bootstrap.token(),
                    "/api/v1/modules/PROJECTS")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"action\":\"execute\",\"values\":{}}"))
                    .build());
            assertEquals(400, invalidAction.statusCode());
        }
    }

    @Test
    void acceptsBoundedDuckyHistoryRequestBodies() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = backend.user;
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());
            String history = "界".repeat(9_000);
            String requestBody = "{\"action\":\"ask\",\"values\":{\"question\":\"Explain this\","
                    + "\"history\":[\"Ducky: " + history + "\"]}}";

            HttpResponse<String> accepted = send(apiRequest(origin, bootstrap.token(), "/api/v1/modules/DUCKY")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build());
            assertEquals(200, accepted.statusCode());
            assertEquals("ask", backend.lastModuleAction);
        }
    }

    @Test
    void routesProjectLocationSearchThroughAuthenticatedBackend() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = backend.user;
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());
            HttpResponse<String> found = send(apiRequest(origin, bootstrap.token(),
                    "/api/v1/map/search?q=Manila%20Philippines").GET().build());
            assertEquals(200, found.statusCode());
            assertEquals("Manila, Philippines", JSON.readTree(found.body()).get(0)
                    .path("displayName").asText());
            assertEquals(1, backend.mapSearchCalls);

            HttpResponse<String> missingQuery = send(apiRequest(origin, bootstrap.token(),
                    "/api/v1/map/search").GET().build());
            assertEquals(400, missingQuery.statusCode());
            assertEquals(1, backend.mapSearchCalls);
        }
    }

    @Test
    void rejectsWrongOriginAndTokenBeforeCallingTheBackend() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());
            URI endpoint = URI.create(bootstrap.origin() + "/api/v1/session");

            HttpResponse<String> wrongOrigin = send(HttpRequest.newBuilder(endpoint)
                    .header("Origin", "http://localhost:5173")
                    .header("Authorization", "Bearer " + bootstrap.token())
                    .GET().build());
            assertEquals(403, wrongOrigin.statusCode());

            HttpResponse<String> wrongToken = send(apiRequest(origin, "wrong-token").GET().build());
            assertEquals(401, wrongToken.statusCode());
            assertEquals(0, backend.loginCalls);

            HttpResponse<String> sameOriginFetch = send(HttpRequest.newBuilder(endpoint)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Authorization", "Bearer " + bootstrap.token())
                    .GET().build());
            assertEquals(401, sameOriginFetch.statusCode());

            HttpResponse<String> crossSiteFetch = send(HttpRequest.newBuilder(endpoint)
                    .header("Sec-Fetch-Site", "cross-site")
                    .header("Authorization", "Bearer " + bootstrap.token())
                    .GET().build());
            assertEquals(403, crossSiteFetch.statusCode());
        }
    }

    @Test
    void servesPackagedWebAssetsWithoutExposingTheNativeBootstrap() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            HttpResponse<String> page = send(HttpRequest.newBuilder(URI.create(bootstrap.origin() + "/"))
                    .GET().build());

            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
            assertFalse(page.body().contains(bootstrap.token()));
            Matcher scriptPath = Pattern.compile("src=\"([^\"]+\\.js)\"").matcher(page.body());
            assertTrue(scriptPath.find());
            HttpResponse<String> script = send(HttpRequest.newBuilder(
                    URI.create(bootstrap.origin() + scriptPath.group(1))).GET().build());
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        }
    }

    @Test
    void nativeHostSignOutUsesTheSameSerializedBackendSession() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.current = backend.user;
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            api.signOutRendererSession();
            assertNull(backend.current());
        }
    }

    @Test
    void validatesRequestBodyAndProvidesConsistentErrors() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LocalApiServer api = LocalApiServer.startForTesting(backend)) {
            LocalApiServer.RendererBootstrap bootstrap = api.rendererBootstrap();
            URI origin = URI.create(bootstrap.origin());

            HttpResponse<String> badType = send(apiRequest(origin, bootstrap.token())
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            assertEquals(415, badType.statusCode());
            assertEquals("unsupported_media_type", JSON.readTree(badType.body()).path("code").asText());

            HttpResponse<String> jsonp = send(apiRequest(origin, bootstrap.token())
                    .header("Content-Type", "application/jsonp")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            assertEquals(415, jsonp.statusCode());

            HttpResponse<String> malformed = send(apiRequest(origin, bootstrap.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{")).build());
            assertEquals(400, malformed.statusCode());
            assertEquals("invalid_request", JSON.readTree(malformed.body()).path("code").asText());

            String oversized = "{\"value\":\"" + "x".repeat(65 * 1024) + "\"}";
            HttpResponse<String> tooLarge = send(apiRequest(origin, bootstrap.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(oversized)).build());
            assertEquals(413, tooLarge.statusCode());
            assertEquals("request_too_large", JSON.readTree(tooLarge.body()).path("code").asText());
            assertEquals(0, backend.loginCalls);
        }
    }

    private static HttpRequest.Builder apiRequest(URI origin, String token) {
        return apiRequest(origin, token, "/api/v1/session");
    }

    private static HttpRequest.Builder apiRequest(URI origin, String token, String path) {
        URI endpoint = URI.create(origin + path);
        return HttpRequest.newBuilder(endpoint)
                .header("Origin", origin.toString())
                .header("Authorization", "Bearer " + token);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final class FakeBackend implements LocalApiBackend {
        private final User user = new User(UUID.randomUUID(), "Test User", "user@example.test", Role.ADMIN, true,
                AccountStatus.APPROVED, Instant.EPOCH, "Test Company", UUID.randomUUID());
        private int loginCalls;
        private int modulePageCalls;
        private int mapSearchCalls;
        private String lastModuleAction;
        private char[] passwordReference;
        private User current;

        @Override public User login(String email, char[] password) throws AuthException {
            loginCalls++;
            assertEquals("user@example.test", email);
            passwordReference = password;
            current = user;
            return user;
        }

        @Override public User current() { return current; }

        @Override public void logout() { current = null; }

        @Override public LocalApiServer.DashboardData dashboard() {
            assertNotNull(current);
            return new LocalApiServer.DashboardData("12.34", "5.67", 1, 2, 1,
                    List.of(new LocalApiServer.ProjectSummary(UUID.randomUUID().toString(),
                            "Test Project", "TEST", 2, "12.34")));
        }

        @Override public LocalApiServer.ModulePageData modulePage(Module module) {
            modulePageCalls++;
            return new LocalApiServer.ModulePageData(module.name(), "Projects", "Workspace data",
                    List.of(), List.of(), List.of(), List.of(new LocalApiServer.ModuleRecord("record-1",
                    Map.of("createdAt", Instant.EPOCH))), true, false, false,
                    "New project", "Save project", "save");
        }

        @Override public LocalApiServer.ModulePageData moduleAction(Module module, String action, String id,
                                                                      JsonNode values) {
            lastModuleAction = action;
            if (!"save".equals(action) && !"ask".equals(action)) {
                throw new IllegalArgumentException("Invalid action.");
            }
            return modulePage(module);
        }

        @Override public List<LocalApiServer.MapSearchResult> mapSearch(String query) {
            mapSearchCalls++;
            assertEquals("Manila Philippines", query);
            return List.of(new LocalApiServer.MapSearchResult(
                    "Manila, Philippines", "14.5995", "120.9842", "city"));
        }
    }
}
