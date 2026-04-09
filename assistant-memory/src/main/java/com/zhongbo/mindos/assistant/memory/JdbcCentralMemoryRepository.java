package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemoryAppendResult;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncSnapshot;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DB-backed central memory repository skeleton.
 *
 * Table expectation: memory_event(id,user_id,event_type,payload_json,event_id,created_at).
 */
public class JdbcCentralMemoryRepository implements CentralMemoryRepository {

    private static final Logger LOGGER = Logger.getLogger(JdbcCentralMemoryRepository.class.getName());
    private static final String MEMORY_EVENT_TABLE = "memory_event";
    private static final String MEMORY_EVENT_IDEMPOTENT_INDEX = "ux_memory_event_user_eventid";
    private static final String MEMORY_EVENT_CURSOR_INDEX = "idx_memory_event_user_cursor";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcCentralMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureSchema();
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

            Long cursor = 0L;
            try {
                Number key = keyHolder.getKey();
                if (key != null) {
                    cursor = key.longValue();
                }
            } catch (RuntimeException ex) {
                // Some drivers (or H2 with certain settings) return multiple generated keys
                // (e.g. ID and CREATED_AT). Fall back to inspecting the key list map and
                // pick a numeric entry (prefer key named 'ID' case-insensitive).
                if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
                    Map<String, Object> keys = keyHolder.getKeyList().get(0);
                    // Try to find 'ID' first
                    for (String candidate : new String[]{"ID", "id"}) {
                        if (keys.containsKey(candidate)) {
                            Object v = keys.get(candidate);
                            if (v instanceof Number n) {
                                cursor = n.longValue();
                                break;
                            }
                            try {
                                cursor = Long.parseLong(String.valueOf(v));
                                break;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    // If not found, pick first numeric value
                    if (cursor == 0L) {
                        for (Map.Entry<String, Object> e : keys.entrySet()) {
                            Object v = e.getValue();
                            if (v instanceof Number n) {
                                cursor = n.longValue();
                                break;
                            }
                            try {
                                cursor = Long.parseLong(String.valueOf(v));
                                break;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
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

    private void ensureSchema() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ensureMemoryEventTable(connection);
            ensureIndex(connection, MEMORY_EVENT_IDEMPOTENT_INDEX,
                    "create unique index " + MEMORY_EVENT_IDEMPOTENT_INDEX + " on " + MEMORY_EVENT_TABLE + "(user_id, event_id)");
            ensureIndex(connection, MEMORY_EVENT_CURSOR_INDEX,
                    "create index " + MEMORY_EVENT_CURSOR_INDEX + " on " + MEMORY_EVENT_TABLE + "(user_id, id)");
            return null;
        });
    }

    private void ensureMemoryEventTable(Connection connection) throws SQLException {
        if (tableExists(connection, MEMORY_EVENT_TABLE)) {
            return;
        }
        String product = databaseProductName(connection);
        String createTableSql = product.contains("mysql")
                ? "create table if not exists memory_event ("
                + "id bigint not null auto_increment primary key, "
                + "user_id varchar(255) not null, "
                + "event_type varchar(64) not null, "
                + "payload_json longtext not null, "
                + "event_id varchar(255), "
                + "created_at timestamp default current_timestamp)"
                : "create table if not exists memory_event ("
                + "id bigint auto_increment primary key, "
                + "user_id varchar(255) not null, "
                + "event_type varchar(64) not null, "
                + "payload_json clob not null, "
                + "event_id varchar(255), "
                + "created_at timestamp default current_timestamp)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSql);
        }
    }

    private void ensureIndex(Connection connection, String indexName, String createSql) throws SQLException {
        if (indexExists(connection, indexName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(createSql);
        } catch (SQLException ex) {
            LOGGER.log(Level.FINE, "Failed to create index " + indexName + " for jdbc central memory repository", ex);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String candidate : List.of(tableName, tableName.toUpperCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT))) {
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), connection.getSchema(), candidate, new String[]{"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, candidate, new String[]{"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String candidate : List.of(indexName, indexName.toUpperCase(Locale.ROOT), indexName.toLowerCase(Locale.ROOT))) {
            try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), connection.getSchema(), MEMORY_EVENT_TABLE, false, false)) {
                while (indexes.next()) {
                    String existing = indexes.getString("INDEX_NAME");
                    if (existing != null && existing.equalsIgnoreCase(candidate)) {
                        return true;
                    }
                }
            }
            try (ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, MEMORY_EVENT_TABLE, false, false)) {
                while (indexes.next()) {
                    String existing = indexes.getString("INDEX_NAME");
                    if (existing != null && existing.equalsIgnoreCase(candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String databaseProductName(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData == null ? "" : metaData.getDatabaseProductName();
            return productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        } catch (SQLException ex) {
            LOGGER.log(Level.FINE, "Failed to detect jdbc database product name", ex);
            return "";
        }
    }
}

