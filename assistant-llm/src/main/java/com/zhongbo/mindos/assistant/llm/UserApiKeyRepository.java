package com.zhongbo.mindos.assistant.llm;

import java.util.Optional;

/**
 * Persistence abstraction for encrypted user API keys.
 *
 * Implementations can use JPA/JDBC and store records in a secure database table.
 */
public interface UserApiKeyRepository {

    void upsert(EncryptedUserApiKeyRecord record);

    Optional<EncryptedUserApiKeyRecord> findByUserId(String userId);
}

