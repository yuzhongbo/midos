package com.zhongbo.mindos.assistant.llm;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class UserApiKeyService {

    private final UserApiKeyRepository userApiKeyRepository;
    private final AesApiKeyCryptoService cryptoService;

    public UserApiKeyService(ObjectProvider<UserApiKeyRepository> userApiKeyRepositoryProvider,
                             AesApiKeyCryptoService cryptoService) {
        this.userApiKeyRepository = userApiKeyRepositoryProvider.getIfAvailable();
        this.cryptoService = cryptoService;
    }

    public void storeUserApiKey(String userId, String plainApiKey) {
        if (userApiKeyRepository == null) {
            throw new UnsupportedOperationException("No UserApiKeyRepository implementation configured");
        }

        String encrypted = cryptoService.encrypt(plainApiKey);
        userApiKeyRepository.upsert(new EncryptedUserApiKeyRecord(userId, encrypted, "v1", Instant.now()));
    }

    public Optional<String> resolveDecryptedApiKey(String userId) {
        if (userApiKeyRepository == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        return userApiKeyRepository.findByUserId(userId)
                .map(EncryptedUserApiKeyRecord::encryptedApiKey)
                .map(cryptoService::decrypt);
    }
}

