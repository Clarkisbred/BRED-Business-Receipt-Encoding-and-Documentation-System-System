package com.bred.rms.repositories.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.models.Attachment;
import com.bred.rms.models.Receipt;
import com.bred.rms.models.ReceiptItem;
import com.bred.rms.models.ReceiptType;
import com.bred.rms.repositories.ReceiptRepository;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.repositories.SavedReceiptReadbackException;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Receipts over PostgREST; writes go through the save_receipt / delete_receipt RPCs. */
public final class SupabaseReceiptRepository implements ReceiptRepository {
    private static final String SELECT = "select=*,receipt_items(*),receipt_attachments(*)";
    private final RestClient rest;

    public SupabaseReceiptRepository(RestClient rest) { this.rest = rest; }

    @Override public List<Receipt> findAll() {
        try {
            List<Receipt> out = new ArrayList<>();
            for (JsonNode row : rest.selectAll("receipts", SELECT + "&order=receipt_date.desc,created_at.desc,id.asc")) out.add(map(row));
            return out;
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Optional<Receipt> findById(UUID id) {
        try {
            JsonNode rows = rest.select("receipts", "id=eq." + id + "&" + SELECT);
            return rows.isArray() && rows.size() > 0 ? Optional.of(map(rows.get(0))) : Optional.empty();
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    @Override public Receipt save(Receipt r) {
        return saveUsingRpc(r, "save_receipt");
    }

    @Override public Receipt saveApprovedImport(Receipt r) {
        if (r.id() != null) {
            throw new RepositoryException("AI receipt imports can only create new receipts.", false);
        }
        Map<String, Object> p = payload(r);
        p.put("attachments", List.of());
        p.remove("company_id");
        return invokeSaveRpc(p, "approve_ai_import_receipt");
    }

    @Override public Receipt saveApprovedImportAttachments(Receipt r) {
        if (r.id() == null) {
            throw new RepositoryException("Save the imported receipt before linking attachments.", false);
        }
        Map<String, Object> p = payload(r);
        p.put("operation", "attachments");
        return invokeSaveRpc(p, "approve_ai_import_receipt");
    }

    private Receipt saveUsingRpc(Receipt r, String rpcName) {
        return invokeSaveRpc(payload(r), rpcName);
    }

    private Receipt invokeSaveRpc(Map<String, Object> p, String rpcName) {
        try {
            JsonNode id = rest.rpc(rpcName, Map.of("p", p));
            UUID savedId = UUID.fromString(id.asText());
            try {
                return findById(savedId).orElseThrow(() ->
                        new RepositoryException("The receipt was saved but could not be read back.", false));
            } catch (RuntimeException e) {
                throw new SavedReceiptReadbackException(savedId, e);
            }
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    private static Map<String, Object> payload(Receipt r) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (r.id() != null) p.put("id", r.id().toString());
        p.put("receipt_no", r.receiptNo().trim());
        p.put("receipt_date", r.date().toString());
        p.put("receipt_type", r.type().dbValue());
        p.put("supplier_id", r.supplierId().toString());
        p.put("project_id", r.projectId() == null ? null : r.projectId().toString());
        p.put("discount", r.discount());
        p.put("vat", r.vat());
        p.put("additional_charges", r.additionalCharges());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReceiptItem i : r.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", i.description());
            m.put("quantity", i.quantity());
            m.put("unit_price", i.unitPrice());
            items.add(m);
        }
        p.put("items", items);
        List<Map<String, Object>> atts = new ArrayList<>();
        for (Attachment a : r.attachments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file_name", a.fileName());
            m.put("storage_path", a.storagePath());
            atts.add(m);
        }
        p.put("attachments", atts);
        return p;
    }

    @Override public List<String> delete(UUID id) {
        try {
            JsonNode paths = rest.rpc("delete_receipt", Map.of("p_id", id.toString()));
            List<String> out = new ArrayList<>();
            if (paths.isArray()) paths.forEach(n -> out.add(n.asText()));
            return out;
        } catch (SupabaseException e) { throw SupabaseErrors.translate(e); }
    }

    private static Receipt map(JsonNode row) {
        List<JsonNode> itemRows = new ArrayList<>();
        row.path("receipt_items").forEach(itemRows::add);
        itemRows.sort(Comparator.comparingInt(n -> n.path("line_no").asInt()));
        List<ReceiptItem> items = new ArrayList<>();
        for (JsonNode n : itemRows) items.add(new ReceiptItem(Json.text(n, "description"), Json.decimal(n, "quantity"), Json.decimal(n, "unit_price")));
        List<Attachment> atts = new ArrayList<>();
        for (JsonNode n : row.path("receipt_attachments")) atts.add(new Attachment(Json.text(n, "file_name"), Json.text(n, "storage_path")));
        return new Receipt(Json.uuid(row, "id"), Json.text(row, "receipt_no"), Json.date(row, "receipt_date"),
                ReceiptType.fromDb(Json.text(row, "receipt_type")), Json.uuid(row, "supplier_id"), Json.uuid(row, "project_id"),
                items, Json.decimal(row, "discount"), Json.decimal(row, "vat"), Json.decimal(row, "additional_charges"),
                atts, Json.uuid(row, "encoded_by"), Json.instant(row, "created_at"), Json.instant(row, "modified_at"),
                Json.uuid(row, "company_id"));
    }
}
