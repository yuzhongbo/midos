package com.zhongbo.mindos.assistant.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SecurityChallengeService {

    public record SecurityChallenge(String token,
                                    String operation,
                                    String resource,
                                    String actor,
                                    String ipAddress,
                                    Instant expiresAt) {
    }

    private static final long DEFAULT_TTL_SECONDS = 120L;

    private final Map<String, SecurityChallenge> challenges = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final long maxTtlSeconds;

    public SecurityChallengeService(@Value("${mindos.security.risky-ops.challenge.max-ttl-seconds:600}") long maxTtlSeconds) {
        this.maxTtlSeconds = Math.max(30L, maxTtlSeconds);
    }

    public SecurityChallenge issue(String operation,
                                   String resource,
                                   String actor,
                                   String ipAddress,
                                   Long ttlSeconds) {
        String op = normalize(operation, "");
        String res = normalize(resource, "*");
        String act = normalize(actor, "unknown");
        String ip = normalize(ipAddress, "*");
        if (op.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }

        long ttl = ttlSeconds == null ? DEFAULT_TTL_SECONDS : Math.max(10L, ttlSeconds);
        ttl = Math.min(ttl, maxTtlSeconds);
        String token = generateToken();
        SecurityChallenge challenge = new SecurityChallenge(
                token,
                op,
                res,
                act,
                ip,
                Instant.now().plusSeconds(ttl)
        );
        challenges.put(token, challenge);
        return challenge;
    }

    public boolean consume(String token,
                           String operation,
                           String resource,
                           String actor,
                           String ipAddress) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SecurityChallenge challenge = challenges.remove(token.trim());
        if (challenge == null) {
            return false;
        }
        if (challenge.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        if (!Objects.equals(challenge.operation(), normalize(operation, ""))) {
            return false;
        }
        String normalizedResource = normalize(resource, "*");
        if (!"*".equals(challenge.resource()) && !Objects.equals(challenge.resource(), normalizedResource)) {
            return false;
        }
        String normalizedActor = normalize(actor, "unknown");
        if (!(Objects.equals(challenge.actor(), normalizedActor) || "*".equals(challenge.actor()))) {
            return false;
        }
        String normalizedIp = normalize(ipAddress, "*");
        return Objects.equals(challenge.ipAddress(), normalizedIp) || "*".equals(challenge.ipAddress());
    }

    private String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

