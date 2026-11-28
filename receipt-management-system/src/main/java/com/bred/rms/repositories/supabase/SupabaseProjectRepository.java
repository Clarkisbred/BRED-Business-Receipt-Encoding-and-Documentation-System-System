package com.bred.rms.repositories.supabase;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.models.Project;
import com.bred.rms.repositories.ProjectRepository;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Projects table access (RLS: active users read/write, only admins delete). */
public final class SupabaseProjectRepository implements ProjectRepository {
    private final RestClient rest;

    public SupabaseProjectRepository(RestClient rest) { this.rest = rest; }

    @Override public List<Project> findAll() {
        try {
            List<Project> out = new ArrayList<>();
            for (JsonNode n : rest.selectAll("projects", "select=*&order=name.asc,id.asc")) out.add(map(n));
            return out;
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Optional<Project> findById(UUID id) {
        try {
            JsonNode rows = rest.select("projects", "select=*&id=eq." + id);
            return rows.isArray() && rows.size() > 0 ? Optional.of(map(rows.get(0))) : Optional.empty();
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Project save(Project p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", p.name().trim());
        row.put("code", p.code().trim());
        row.put("fund_source", p.fundSource() == null ? "" : p.fundSource().trim());
        row.put("contract_amount", p.contractAmount());
        row.put("revised_contract_amount", p.revisedContractAmount());
        try {
            JsonNode saved = p.id() == null ? rest.insert("projects", row) : rest.patch("projects", "id=eq." + p.id(), row);
            if (!saved.isArray() || saved.isEmpty()) throw new RepositoryException("You don't have permission to do that.", false);
            return map(saved.get(0));
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public void delete(UUID id) {
        try {
            JsonNode deleted = rest.delete("projects", "id=eq." + id);
            if (deleted.isArray() && deleted.isEmpty()) throw new RepositoryException("You don't have permission to do that.", false);
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    private static Project map(JsonNode n) {
        BigDecimal contractAmount = Json.decimal(n, "contract_amount");
        BigDecimal revisedContractAmount = n.has("revised_contract_amount") && !n.path("revised_contract_amount").isNull()
                ? Json.decimal(n, "revised_contract_amount") : contractAmount;
        return new Project(Json.uuid(n, "id"), Json.text(n, "name"), Json.text(n, "code"),
                Json.text(n, "fund_source"), contractAmount, revisedContractAmount);
    }
}
