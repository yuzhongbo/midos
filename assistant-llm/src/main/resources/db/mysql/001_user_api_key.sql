-- Skeleton schema for encrypted per-user LLM API keys

CREATE TABLE IF NOT EXISTS user_llm_api_key (
    user_id VARCHAR(128) PRIMARY KEY,
    encrypted_api_key TEXT NOT NULL,
    key_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

