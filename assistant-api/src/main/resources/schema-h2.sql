-- Schema for MindOS central memory (H2)
-- Creates memory_event table and unique index for (user_id, event_id)
CREATE TABLE IF NOT EXISTS memory_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_json CLOB NOT NULL,
  event_id VARCHAR(255),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_memory_event_user_eventid ON memory_event(user_id, event_id);

