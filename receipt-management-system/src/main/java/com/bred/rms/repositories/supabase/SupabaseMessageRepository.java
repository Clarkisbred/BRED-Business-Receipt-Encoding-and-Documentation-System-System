package com.bred.rms.repositories.supabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.bred.rms.models.ChatMessage;
import com.bred.rms.repositories.MessageRepository;
import com.bred.rms.repositories.RepositoryException;
import com.bred.rms.supabase.RestClient;
import com.bred.rms.supabase.SupabaseException;

import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Direct messages stored in Supabase; row-level security limits reads to participants. */
public final class SupabaseMessageRepository implements MessageRepository {
    private static final int MAX_BODY_LENGTH = 4000;
    private final RestClient rest;

    public SupabaseMessageRepository(RestClient rest) {
        this.rest = rest;
    }

    @Override public List<ChatMessage> findFor(UUID userId) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            String query = "select=id,sender_id,recipient_id,company_id,sender_name,sender_email,recipient_name,body,sent_at,is_broadcast,is_urgent"
                    + "&recipient_id=not.is.null"
                    + "&or=(sender_id.eq." + userId + ",recipient_id.eq." + userId + ")"
                    + "&order=sent_at.asc,id.asc";
            for (JsonNode row : rest.selectAll("custom_messages", query)) messages.add(map(row));
            return List.copyOf(messages);
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    @Override public List<ChatMessage> findCompanyChat(UUID companyId) {
        if (companyId == null) return List.of();
        try {
            List<ChatMessage> messages = new ArrayList<>();
            String query = "select=id,sender_id,recipient_id,company_id,sender_name,sender_email,recipient_name,body,sent_at,is_broadcast,is_urgent"
                    + "&company_id=eq." + companyId
                    + "&recipient_id=is.null"
                    + "&order=sent_at.asc,id.asc";
            for (JsonNode row : rest.selectAll("custom_messages", query)) messages.add(map(row));
            return List.copyOf(messages);
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    @Override public ChatMessage sendCompanyChat(UUID companyId, String body, boolean urgent) {
        if (companyId == null) throw new RepositoryException("Your account is not assigned to a company.", false);
        String text = validateBody(body);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recipient_id", null);
        row.put("company_id", companyId.toString());
        row.put("body", text);
        row.put("is_urgent", urgent);
        try {
            JsonNode result = rest.insert("custom_messages", row);
            if (!result.isArray() || result.isEmpty()) {
                throw new RepositoryException("The message was not saved. Please try again.", false);
            }
            return map(result.get(0));
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    @Override public ChatMessage send(UUID recipientId, String body) {
        String text = validateBody(body);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recipient_id", recipientId.toString());
        row.put("body", text);
        try {
            JsonNode result = rest.insert("custom_messages", row);
            if (!result.isArray() || result.isEmpty()) {
                throw new RepositoryException("The message was not saved. Please try again.", false);
            }
            return map(result.get(0));
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    @Override public List<ChatMessage> findHistory() {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            for (JsonNode row : rest.rpcAll("creator_message_history", Map.of())) messages.add(map(row));
            return List.copyOf(messages);
        } catch (SupabaseException e) {
            if (e.status() == 404) {
                throw new RepositoryException(
                        "Creator chat history requires database migration 011 to be applied.", false);
            }
            throw SupabaseErrors.translate(e);
        }
    }

    @Override public int sendBroadcast(List<UUID> recipientIds, String body) {
        String text = validateBody(body);
        List<String> ids = recipientIds == null ? List.of()
                : recipientIds.stream().map(UUID::toString).toList();
        try {
            JsonNode result = rest.rpc("creator_broadcast", Map.of("p_recipient_ids", ids, "p_body", text));
            if (result == null || !result.isNumber()) {
                throw new RepositoryException("Cloud Database did not confirm the broadcast.", false);
            }
            return result.asInt();
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    @Override public List<ChatMessage> findBroadcastsFor(UUID userId, Instant since) {
        if (userId == null || since == null) return List.of();
        try {
            String encodedSince = URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
            String query = "select=id,sender_id,recipient_id,company_id,sender_name,sender_email,recipient_name,body,sent_at,is_broadcast,is_urgent"
                    + "&recipient_id=eq." + userId
                    + "&is_broadcast=eq.true"
                    + "&sent_at=gte." + encodedSince
                    + "&order=sent_at.asc,id.asc";
            List<ChatMessage> messages = new ArrayList<>();
            for (JsonNode row : rest.selectAll("custom_messages", query)) messages.add(map(row));
            return List.copyOf(messages);
        } catch (SupabaseException e) {
            throw SupabaseErrors.translate(e);
        }
    }

    private static String validateBody(String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw new RepositoryException("Write a message before sending.", false);
        if (text.length() > MAX_BODY_LENGTH) {
            throw new RepositoryException("Messages can be up to " + MAX_BODY_LENGTH + " characters.", false);
        }
        return text;
    }

    private static ChatMessage map(JsonNode row) {
        return new ChatMessage(Json.uuid(row, "id"), Json.uuid(row, "sender_id"),
                Json.uuid(row, "recipient_id"), Json.text(row, "body"), Json.instant(row, "sent_at"),
                row.path("is_broadcast").asBoolean(false), Json.text(row, "sender_name"),
                Json.text(row, "sender_email"),
                Json.text(row, "recipient_name"), Json.uuid(row, "company_id"),
                row.path("is_urgent").asBoolean(false));
    }
}
