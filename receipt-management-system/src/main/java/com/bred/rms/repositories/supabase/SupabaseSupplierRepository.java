package com.bred.rms.repositories.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.models.Supplier;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.SupplierRepository;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Suppliers table access (RLS: active users read/write, only admins delete). */
public final class SupabaseSupplierRepository implements SupplierRepository {
    private final RestClient rest;

    public SupabaseSupplierRepository(RestClient rest) { this.rest = rest; }

    @Override public List<Supplier> findAll() {
        try {
            List<Supplier> out = new ArrayList<>();
            for (JsonNode n : rest.selectAll("suppliers", "select=*&order=business_name.asc,id.asc")) out.add(map(n));
            return out;
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Optional<Supplier> findById(UUID id) {
        try {
            JsonNode rows = rest.select("suppliers", "select=*&id=eq." + id);
            return rows.isArray() && rows.size() > 0 ? Optional.of(map(rows.get(0))) : Optional.empty();
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Supplier save(Supplier s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("business_name", s.businessName().trim());
        row.put("tin", s.tin().trim());
        row.put("address", s.address() == null ? "" : s.address().trim());
        row.put("contact_number", s.contactNumber() == null ? "" : s.contactNumber().trim());
        row.put("tax_classification", s.taxClassification());
        try {
            JsonNode saved = s.id() == null ? rest.insert("suppliers", row) : rest.patch("suppliers", "id=eq." + s.id(), row);
            if (!saved.isArray() || saved.isEmpty()) throw new RepositoryException("You don't have permission to do that.", false);
            return map(saved.get(0));
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public void delete(UUID id) {
        try {
            JsonNode deleted = rest.delete("suppliers", "id=eq." + id);
            if (deleted.isArray() && deleted.isEmpty()) throw new RepositoryException("You don't have permission to do that.", false);
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    private static Supplier map(JsonNode n) {
        return new Supplier(Json.uuid(n, "id"), Json.text(n, "business_name"), Json.text(n, "tin"),
                Json.text(n, "address"), Json.text(n, "contact_number"),
                n.hasNonNull("tax_classification") ? Json.text(n, "tax_classification") : "UNCLASSIFIED");
    }
}
