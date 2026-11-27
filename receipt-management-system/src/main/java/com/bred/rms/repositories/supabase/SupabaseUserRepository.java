package com.bred.rms.repositories.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.models.AccountStatus;
import com.bred.rms.models.Company;
import com.bred.rms.models.Role;
import com.bred.rms.models.User;
import com.bred.rms.models.ValidationException;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.UserRepository;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Profiles over PostgREST; account creation through the admin-create-user Edge Function. */
public final class SupabaseUserRepository implements UserRepository {
    private static final String COLUMNS = "select=id,full_name,email,role,is_active,account_status,created_at,company_id,companies(name)";
    private final RestClient rest;

    public SupabaseUserRepository(RestClient rest) { this.rest = rest; }

    @Override public List<User> findAll() {
        try {
            List<User> out = new ArrayList<>();
            for (JsonNode n : rest.selectAll("profiles", COLUMNS + "&order=full_name.asc,id.asc")) out.add(map(n));
            return out;
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public List<User> findMessagingContacts() {
        try {
            List<User> out = new ArrayList<>();
            JsonNode contacts = rest.rpc("message_contacts", Map.of());
            if (!contacts.isArray()) {
                throw new RepositoryException("Cloud Database returned an invalid teammate list.", false);
            }
            for (JsonNode n : contacts) {
                out.add(new User(Json.uuid(n, "id"), Json.text(n, "full_name"), "",
                        Role.fromDb(Json.text(n, "role")), true, AccountStatus.APPROVED,
                        java.time.Instant.EPOCH, Json.text(n, "company_name")));
            }
            return out;
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Optional<User> findById(UUID id) {
        try {
            JsonNode rows = rest.select("profiles", COLUMNS + "&id=eq." + id);
            return rows.isArray() && rows.size() > 0 ? Optional.of(map(rows.get(0))) : Optional.empty();
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public User create(String email, String fullName, Role role, char[] password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email.trim());
        body.put("password", new String(password));
        body.put("full_name", fullName.trim());
        body.put("role", role.dbValue());
        try {
            JsonNode res = rest.invokeFunction("admin-create-user", body);
            if (!res.hasNonNull("id")) throw new RepositoryException("The account could not be created.", false);
            return new User(UUID.fromString(res.get("id").asText()), fullName.trim(), email.trim(), role, true,
                    AccountStatus.APPROVED, java.time.Instant.now());
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public void registerAccount(String fullName, String email, Role role, UUID companyId,
                                          String companyName, char[] password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("full_name", fullName.trim());
        body.put("email", email.trim());
        body.put("password", new String(password));
        body.put("role", role.dbValue());
        if (role == Role.ADMIN) body.put("company_name", companyName.trim());
        else body.put("company_id", companyId.toString());
        try {
            rest.invokeFunction("register-account", body);
        } catch (SupabaseException e) { throw registrationFunctionError(e); }
    }

    @Override public List<Company> availableCompanies() {
        try {
            List<Company> companies = new ArrayList<>();
            for (JsonNode node : rest.getFunction("register-account", "mode=companies")) {
                companies.add(new Company(Json.uuid(node, "id"), Json.text(node, "name")));
            }
            return List.copyOf(companies);
        } catch (SupabaseException e) { throw registrationFunctionError(e); }
    }

    @Override public void reviewRegistration(UUID id, AccountStatus decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_id", id.toString());
        body.put("action", decision == AccountStatus.APPROVED ? "APPROVE" : "REJECT");
        try {
            rest.invokeFunction("review-account", body);
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public void update(UUID id, String fullName, Role role, boolean active) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("p_id", id.toString());
        args.put("p_full_name", fullName);
        args.put("p_role", role.dbValue());
        args.put("p_active", active);
        try {
            rest.rpc("admin_update_profile", args);
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public void delete(UUID id) {
        try {
            rest.invokeFunction("admin-delete-user", Map.of("user_id", id.toString()));
        } catch (SupabaseException e) {
            throw adminFunctionError("admin-delete-user", e);
        }
    }

    @Override public void sendPasswordReset(UUID id) {
        try {
            rest.invokeFunction("admin-send-password-reset", Map.of("user_id", id.toString()));
        } catch (SupabaseException e) {
        throw adminFunctionError("admin-send-password-reset", e);
        }
    }

    private static User map(JsonNode n) {
        return new User(Json.uuid(n, "id"), Json.text(n, "full_name"), Json.text(n, "email"),
                Role.fromDb(Json.text(n, "role")), !n.hasNonNull("is_active") || n.get("is_active").asBoolean(),
                AccountStatus.fromDb(Json.text(n, "account_status")),
                n.hasNonNull("created_at") ? Json.instant(n, "created_at") : java.time.Instant.EPOCH,
                n.path("companies").path("name").asText(""),
                n.hasNonNull("company_id") ? Json.uuid(n, "company_id") : null);
    }

    /** A 404 from the Functions gateway means the function isn't deployed; a 404 from the function itself is a missing account. */
    private static RuntimeException adminFunctionError(String function, SupabaseException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (e.status() == 404 && m.contains("function")) {
            return new RepositoryException("The account service is unavailable. Contact your administrator.", false);
        }
        if (e.status() == 404) {
            return new ValidationException(e.getMessage());
        }
        if (e.status() == 502 || e.status() == 500) {
            return new RepositoryException(e.getMessage() == null || e.getMessage().isBlank()
                 ? "The server had a problem. Please try again." : e.getMessage(), false);
        }
        return SupabaseErrors.translate(e);
    }

    private static RuntimeException registrationFunctionError(SupabaseException e) {
        if (e.status() == 404) {
            return new RepositoryException(
                    "The account service is unavailable or outdated. Contact your administrator.",
                    false);
        }
        if (e.status() == 400 && e.getMessage() != null
                && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("choose an available company")) {
            return new RepositoryException(
                    "The account service could not process this registration. Restart BRED and try again. "
                            + "If the issue continues, contact your administrator.",
                    false);
        }
        return SupabaseErrors.translate(e);
    }
}
