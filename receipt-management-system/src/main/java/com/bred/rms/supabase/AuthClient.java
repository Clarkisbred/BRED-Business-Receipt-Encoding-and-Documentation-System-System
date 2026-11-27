package com.bred.rms.supabase;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Supabase Auth (GoTrue) REST calls. Passwords are sent to Supabase only; never stored. */
public final class AuthClient {
    private final SupabaseHttp http;

    /** Creates an auth client instance. */
    public AuthClient(SupabaseHttp http) { this.http = http; }

    /** Signs in. */
    public Session signIn(String email, String password) throws SupabaseException {
        String body = SupabaseHttp.JSON.createObjectNode().put("email", email).put("password", password).toString();
        return parse(http.send("POST", "/auth/v1/token?grant_type=password", body, null, null));
    }

    /** Refreshes the access token using the session's refresh token. */
    public Session refresh(String refreshToken) throws SupabaseException {
        String body = SupabaseHttp.JSON.createObjectNode().put("refresh_token", refreshToken).toString();
        return parse(http.send("POST", "/auth/v1/token?grant_type=refresh_token", body, null, null));
    }

    /** Signs out. */
    public void signOut(String accessToken) throws SupabaseException {
        http.send("POST", "/auth/v1/logout", null, accessToken, null);
    }

    /** Changes the signed-in user's own password. */
    public void updatePassword(String accessToken, String newPassword) throws SupabaseException {
        String body = SupabaseHttp.JSON.createObjectNode().put("password", newPassword).toString();
        http.send("PUT", "/auth/v1/user", body, accessToken, null);
    }

    /** Parses a Supabase authentication response into a session. */
    private static Session parse(JsonNode n) throws SupabaseException {
        if (!n.hasNonNull("access_token") || !n.path("user").hasNonNull("id")) {
            throw new SupabaseException(500, "Unexpected response from the authentication service.");
        }
        long ttl = n.path("expires_in").asLong(3600);
        return new Session(n.get("access_token").asText(), n.path("refresh_token").asText(),
                Instant.now().plusSeconds(ttl), UUID.fromString(n.path("user").get("id").asText()),
                n.path("user").path("email").asText(""));
    }
}
