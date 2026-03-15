-- Central memory event stream for multi-terminal sync (DB-backed repository)

CREATE TABLE IF NOT EXISTS memory_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    event_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_memory_event_user_event_id (user_id, event_id),
    INDEX idx_memory_event_user_cursor (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

