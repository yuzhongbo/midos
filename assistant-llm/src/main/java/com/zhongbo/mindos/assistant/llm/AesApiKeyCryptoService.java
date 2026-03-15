package com.zhongbo.mindos.assistant.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesApiKeyCryptoService {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final String encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesApiKeyCryptoService(@Value("${mindos.llm.encryption-key:}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String encrypt(String plaintext) {
        try {
            SecretKeySpec keySpec = buildKeySpec();
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt API key", ex);
        }
    }

    public String decrypt(String encryptedValue) {
        try {
            String[] parts = encryptedValue.split("\\.", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted payload format");
            }

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, buildKeySpec(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt API key", ex);
        }
    }

    private SecretKeySpec buildKeySpec() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("mindos.llm.encryption-key is not configured");
        }

        byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
        int length = keyBytes.length;
        if (length != 16 && length != 24 && length != 32) {
            throw new IllegalStateException("AES key must be 16, 24, or 32 bytes after Base64 decode");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}

