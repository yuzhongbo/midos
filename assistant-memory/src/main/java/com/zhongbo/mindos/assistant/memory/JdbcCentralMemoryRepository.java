package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * DB-backed central memory repository skeleton.
 *
 * Table expectation: memory_event(id,user_id,event_type,payload_json,event_id,created_at).
 */
public class JdbcCentralMemoryRepository implements CentralMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcCentralMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryAppendResult appendEpisodic(String userId, ConversationTurn turn, String eventId) {
        return appendEvent(userId, "episodic", turn, eventId);
    }

    @Override
    public MemoryAppendResult appendSemantic(String userId, SemanticMemoryEntry entry, String eventId) {
        return appendEvent(userId, "semantic", entry, eventId);
    }

    @Override
    public MemoryAppendResult appendProcedural(String userId, ProceduralMemoryEntry entry, String eventId) {
        return appendEvent(userId, "procedural", entry, eventId);
    }

    @Override
    public MemorySyncSnapshot fetchSince(String userId, long sinceCursorExclusive, int limit) {
        if (limit <= 0) {
            return MemorySyncSnapshot.empty(sinceCursorExclusive);
        }

        String sql = "select id, event_type, payload_json from memory_event "
                + "where user_id = ? and id > ? order by id asc limit ?";

        List<RowEvent> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new RowEvent(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("payload_json")
        ), userId, sinceCursorExclusive, limit);

        List<ConversationTurn> episodic = new ArrayList<>();
        List<SemanticMemoryEntry> semantic = new ArrayList<>();
        List<ProceduralMemoryEntry> procedural = new ArrayList<>();

        long cursor = sinceCursorExclusive;
        for (RowEvent row : rows) {
            cursor = Math.max(cursor, row.id());
            if ("episodic".equals(row.eventType())) {
                episodic.add(readPayload(row.payloadJson(), ConversationTurn.class));
            } else if ("semantic".equals(row.eventType())) {
                semantic.add(readPayload(row.payloadJson(), SemanticMemoryEntry.class));
            } else if ("procedural".equals(row.eventType())) {
                procedural.add(readPayload(row.payloadJson(), ProceduralMemoryEntry.class));
            }
        }

        // Keep deterministic ordering by created sequence.
        episodic.sort(Comparator.comparing(ConversationTurn::createdAt));
        semantic.sort(Comparator.comparing(SemanticMemoryEntry::createdAt));
        procedural.sort(Comparator.comparing(ProceduralMemoryEntry::createdAt));
        return new MemorySyncSnapshot(cursor, episodic, semantic, procedural);
    }

    private MemoryAppendResult appendEvent(String userId, String eventType, Object payload, String eventId) {
        String payloadJson = writePayload(payload);
        String effectiveEventId = normalizeEventId(eventId);

        try {
            String insertSql = "insert into memory_event(user_id, event_type, payload_json, event_id) values (?, ?, ?, ?)";
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, userId);
                ps.setString(2, eventType);
                ps.setString(3, payloadJson);
                ps.setString(4, effectiveEventId);
                return ps;
            }, keyHolder);

            Number key = keyHolder.getKey();
            Long cursor = key == null ? 0L : key.longValue();
            return new MemoryAppendResult(cursor, true);
        } catch (DuplicateKeyException duplicateKeyException) {
            if (effectiveEventId == null) {
                throw duplicateKeyException;
            }
            Long existingCursor = jdbcTemplate.queryForObject(
                    "select id from memory_event where user_id = ? and event_id = ? limit 1",
                    Long.class,
                    userId,
                    effectiveEventId
            );
            return new MemoryAppendResult(existingCursor == null ? 0L : existingCursor, false);
        }
    }

    private String normalizeEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        return eventId.trim();
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize memory event payload", e);
        }
    }

    private <T> T readPayload(String payloadJson, Class<T> type) {
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize memory event payload: " + type.getSimpleName(), e);
        }
    }

    private record RowEvent(long id, String eventType, String payloadJson) {
    }
}

