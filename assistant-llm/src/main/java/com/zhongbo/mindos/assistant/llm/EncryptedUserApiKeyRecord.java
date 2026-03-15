package com.zhongbo.mindos.assistant.llm;

import java.time.Instant;

/**
 * Database storage record for encrypted per-user API keys.
 */
public record EncryptedUserApiKeyRecord(
        String userId,
        String encryptedApiKey,
        String keyVersion,
        Instant updatedAt
) {
}

