package com.zhongbo.mindos.assistant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.common.dto.SecurityAuditEventDto;
import com.zhongbo.mindos.assistant.common.dto.SecurityAuditQueryResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Service
public class SecurityAuditLogService {

    private static final Logger LOGGER = Logger.getLogger(SecurityAuditLogService.class.getName());

    private final boolean enabled;
    private final Path auditFile;
    private final String traceIdHeader;
    private final Map<String, byte[]> cursorSigningKeysByVersion;
    private final String activeCursorKeyVersion;
    private final long cursorTtlSeconds;
    private final ObjectMapper objectMapper;

    public SecurityAuditLogService(
            @Value("${mindos.security.audit.enabled:true}") boolean enabled,
            @Value("${mindos.security.audit.file:logs/security-audit.log}") String auditFile,
            @Value("${mindos.security.audit.trace-id-header:X-Trace-Id}") String traceIdHeader,
            @Value("${mindos.security.audit.cursor-signing-key:mindos-audit-cursor-key-change-me}") String cursorSigningKey,
            @Value("${mindos.security.audit.cursor-active-key-version:v1}") String activeCursorKeyVersion,
            @Value("${mindos.security.audit.cursor-signing-keys:}") String cursorSigningKeys,
            @Value("${mindos.security.audit.cursor-ttl-seconds:300}") long cursorTtlSeconds) {
        this.enabled = enabled;
        this.auditFile = Paths.get(auditFile == null || auditFile.isBlank() ? "logs/security-audit.log" : auditFile.trim());
        this.traceIdHeader = traceIdHeader == null || traceIdHeader.isBlank() ? "X-Trace-Id" : traceIdHeader.trim();
        this.activeCursorKeyVersion = normalizeFilter(activeCursorKeyVersion).isBlank() ? "v1" : normalizeFilter(activeCursorKeyVersion);
        this.cursorSigningKeysByVersion = parseSigningKeys(cursorSigningKey, cursorSigningKeys, this.activeCursorKeyVersion);
        this.cursorTtlSeconds = Math.max(30L, cursorTtlSeconds);
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public synchronized void record(String actor,
                                    String operation,
                                    String resource,
                                    String result,
                                    String reason,
                                    String remoteAddress,
                                    String userAgent) {
        record(null, actor, operation, resource, result, reason, remoteAddress, userAgent);
    }

    public synchronized void record(String traceId,
                                    String actor,
                                    String operation,
                                    String resource,
                                    String result,
                                    String reason,
                                    String remoteAddress,
                                    String userAgent) {
        if (!enabled) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now());
        event.put("traceId", safe(traceId));
        event.put("actor", safe(actor));
        event.put("operation", safe(operation));
        event.put("resource", safe(resource));
        event.put("result", safe(result));
        event.put("reason", safe(reason));
        event.put("remoteAddress", safe(remoteAddress));
        event.put("userAgent", safe(userAgent));

        try {
            Path parent = auditFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(auditFile, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to append security audit log", ex);
        }
    }

    public List<SecurityAuditEventDto> readRecent(int limit) {
        int effectiveLimit = Math.max(1, limit);
        if (!Files.exists(auditFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
            List<SecurityAuditEventDto> events = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && events.size() < effectiveLimit; i--) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> parsed = objectMapper.readValue(line, new TypeReference<>() {});
                    events.add(new SecurityAuditEventDto(
                            parseInstant(parsed.get("timestamp")),
                            asText(parsed.get("traceId")),
                            asText(parsed.get("actor")),
                            asText(parsed.get("operation")),
                            asText(parsed.get("resource")),
                            asText(parsed.get("result")),
                            asText(parsed.get("reason")),
                            asText(parsed.get("remoteAddress")),
                            asText(parsed.get("userAgent"))
                    ));
                } catch (Exception ignored) {
                    // Ignore malformed audit line and continue.
                }
            }
            return List.copyOf(events);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to read security audit log", ex);
            return List.of();
        }
    }

    public SecurityAuditQueryResponseDto queryRecent(int limit,
                                                     String cursor,
                                                     String actor,
                                                     String operation,
                                                     String result,
                                                     String traceId,
                                                     String from,
                                                     String to) {
        int effectiveLimit = Math.max(1, limit);
        String actorFilter = normalizeFilter(actor);
        String operationFilter = normalizeFilter(operation);
        String resultFilter = normalizeFilter(result);
        String traceIdFilter = normalizeFilter(traceId);
        String fromFilter = normalizeFilter(from);
        String toFilter = normalizeFilter(to);
        Instant fromInstant = parseOptionalInstant(fromFilter);
        Instant toInstant = parseOptionalInstant(toFilter);
        ParsedCursor parsedCursor = parseSignedCursor(cursor, actorFilter, operationFilter, resultFilter, traceIdFilter, fromFilter, toFilter);
        int offset = parsedCursor.offset();
        String cursorKeyVersion = parsedCursor.keyVersion();

        if (!Files.exists(auditFile)) {
            return new SecurityAuditQueryResponseDto(
                    List.of(),
                    effectiveLimit,
                    safeCursor(cursor),
                    "",
                    "",
                    cursorKeyVersion,
                    actorFilter,
                    operationFilter,
                    resultFilter,
                    traceIdFilter,
                    fromFilter,
                    toFilter
            );
        }

        try {
            List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
            List<SecurityAuditEventDto> matched = new ArrayList<>();
            int matchedSeen = 0;
            boolean hasMore = false;

            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                SecurityAuditEventDto event = parseAuditEvent(line);
                if (event == null || !matchesFilters(event, actorFilter, operationFilter, resultFilter, traceIdFilter, fromInstant, toInstant)) {
                    continue;
                }
                if (matchedSeen++ < offset) {
                    continue;
                }
                if (matched.size() < effectiveLimit) {
                    matched.add(event);
                } else {
                    hasMore = true;
                    break;
                }
            }

            Instant cursorExpiresAt = hasMore ? Instant.now().plusSeconds(cursorTtlSeconds) : null;
            String nextCursor = hasMore
                    ? signCursor(offset + matched.size(), actorFilter, operationFilter, resultFilter, traceIdFilter, fromFilter, toFilter, cursorExpiresAt)
                    : "";
            String responseCursorKeyVersion = hasMore ? activeCursorKeyVersion : cursorKeyVersion;
            return new SecurityAuditQueryResponseDto(
                    List.copyOf(matched),
                    effectiveLimit,
                    safeCursor(cursor),
                    nextCursor,
                    cursorExpiresAt == null ? "" : cursorExpiresAt.toString(),
                    responseCursorKeyVersion,
                    actorFilter,
                    operationFilter,
                    resultFilter,
                    traceIdFilter,
                    fromFilter,
                    toFilter
            );
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to query security audit log", ex);
            return new SecurityAuditQueryResponseDto(
                    List.of(),
                    effectiveLimit,
                    safeCursor(cursor),
                    "",
                    "",
                    cursorKeyVersion,
                    actorFilter,
                    operationFilter,
                    resultFilter,
                    traceIdFilter,
                    fromFilter,
                    toFilter
            );
        }
    }

    public String resolveTraceId(HttpServletRequest request) {
        String fromHeader = request == null ? null : request.getHeader(traceIdHeader);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        return UUID.randomUUID().toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized;
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ParsedCursor parseSignedCursor(String cursor,
                                           String actor,
                                           String operation,
                                           String result,
                                           String traceId,
                                           String from,
                                           String to) {
        if (cursor == null || cursor.isBlank()) {
            return new ParsedCursor(0, activeCursorKeyVersion);
        }
        String raw = cursor.trim();
        if (raw.matches("\\d+")) {
            return new ParsedCursor(Math.max(0, Integer.parseInt(raw)), activeCursorKeyVersion);
        }
        String[] parts = raw.split("\\.");
        if (parts.length == 2) {
            return parseLegacyCursor(parts[0], parts[1], actor, operation, result, traceId, from, to);
        }
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid cursor format");
        }
        String headerEncoded = parts[0];
        String payloadEncoded = parts[1];
        String signature = parts[2];
        String keyVersion = verifyJwtHeader(headerEncoded);
        String signingInput = headerEncoded + "." + payloadEncoded;
        String expected = sign(signingInput, keyVersion);
        if (!expected.equals(signature)) {
            throw new IllegalArgumentException("cursor signature mismatch");
        }
        int offset = parseJwtPayload(payloadEncoded, actor, operation, result, traceId, from, to);
        return new ParsedCursor(offset, keyVersion);
    }

    private ParsedCursor parseLegacyCursor(String payloadEncoded,
                                           String signature,
                                           String actor,
                                           String operation,
                                           String result,
                                           String traceId,
                                           String from,
                                           String to) {
        String expected = sign(payloadEncoded, activeCursorKeyVersion);
        if (!expected.equals(signature)) {
            throw new IllegalArgumentException("cursor signature mismatch");
        }
        int offset = parsePayload(payloadEncoded, actor, operation, result, traceId, from, to, false);
        return new ParsedCursor(offset, activeCursorKeyVersion);
    }

    private record ParsedCursor(int offset, String keyVersion) {
    }

    private int parseJwtPayload(String payloadEncoded,
                                String actor,
                                String operation,
                                String result,
                                String traceId,
                                String from,
                                String to) {
        return parsePayload(payloadEncoded, actor, operation, result, traceId, from, to, true);
    }

    private int parsePayload(String payloadEncoded,
                             String actor,
                             String operation,
                             String result,
                             String traceId,
                             String from,
                             String to,
                             boolean checkExpiry) {
        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(payloadEncoded), StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            int offset = Math.max(0, Integer.parseInt(String.valueOf(payload.getOrDefault("offset", "0"))));
            ensureEquals(payload.get("actor"), actor, "actor");
            ensureEquals(payload.get("operation"), operation, "operation");
            ensureEquals(payload.get("result"), result, "result");
            ensureEquals(payload.get("traceId"), traceId, "traceId");
            ensureEquals(payload.get("from"), from, "from");
            ensureEquals(payload.get("to"), to, "to");
            if (checkExpiry) {
                long expEpochSeconds = Long.parseLong(String.valueOf(payload.getOrDefault("exp", "0")));
                if (Instant.now().isAfter(Instant.ofEpochSecond(expEpochSeconds))) {
                    throw new IllegalArgumentException("cursor expired");
                }
            }
            return offset;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid cursor payload", ex);
        }
    }

    private String signCursor(int offset,
                              String actor,
                              String operation,
                              String result,
                              String traceId,
                              String from,
                              String to,
                              Instant expiresAt) {
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"" + activeCursorKeyVersion + "\"}";
        String headerEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offset", Math.max(0, offset));
        payload.put("actor", safe(actor));
        payload.put("operation", safe(operation));
        payload.put("result", safe(result));
        payload.put("traceId", safe(traceId));
        payload.put("from", safe(from));
        payload.put("to", safe(to));
        payload.put("exp", (expiresAt == null ? Instant.now().plusSeconds(cursorTtlSeconds) : expiresAt).getEpochSecond());
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = headerEncoded + "." + payloadEncoded;
            return signingInput + "." + sign(signingInput, activeCursorKeyVersion);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign cursor", ex);
        }
    }

    private String verifyJwtHeader(String headerEncoded) {
        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(headerEncoded), StandardCharsets.UTF_8);
            Map<String, Object> header = objectMapper.readValue(headerJson, new TypeReference<>() {});
            if (!"HS256".equals(String.valueOf(header.getOrDefault("alg", "")))) {
                throw new IllegalArgumentException("unsupported cursor algorithm");
            }
            String keyVersion = String.valueOf(header.getOrDefault("kid", activeCursorKeyVersion));
            if (!cursorSigningKeysByVersion.containsKey(keyVersion)) {
                throw new IllegalArgumentException("unknown cursor key version: " + keyVersion);
            }
            return keyVersion;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid cursor header", ex);
        }
    }

    private String sign(String text, String keyVersion) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = cursorSigningKeysByVersion.get(keyVersion);
            if (key == null || key.length == 0) {
                throw new IllegalArgumentException("missing signing key for version: " + keyVersion);
            }
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("cursor signing failed", ex);
        }
    }

    private Map<String, byte[]> parseSigningKeys(String fallbackSigningKey,
                                                 String rawKeyring,
                                                 String activeVersion) {
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        if (rawKeyring != null && !rawKeyring.isBlank()) {
            Arrays.stream(rawKeyring.split(","))
                    .map(String::trim)
                    .filter(entry -> !entry.isBlank())
                    .forEach(entry -> {
                        int sep = entry.indexOf(':');
                        if (sep <= 0 || sep >= entry.length() - 1) {
                            throw new IllegalArgumentException("Invalid cursor signing key entry: " + entry);
                        }
                        String version = entry.substring(0, sep).trim();
                        String secret = entry.substring(sep + 1).trim();
                        if (version.isBlank() || secret.isBlank()) {
                            throw new IllegalArgumentException("Invalid cursor signing key entry: " + entry);
                        }
                        parsed.put(version, secret.getBytes(StandardCharsets.UTF_8));
                    });
        }
        if (!parsed.containsKey(activeVersion)) {
            String fallback = fallbackSigningKey == null ? "" : fallbackSigningKey.trim();
            if (fallback.isBlank()) {
                throw new IllegalArgumentException("Missing cursor signing key for active version: " + activeVersion);
            }
            parsed.put(activeVersion, fallback.getBytes(StandardCharsets.UTF_8));
        }
        return Map.copyOf(parsed);
    }

    private void ensureEquals(Object actual, String expected, String field) {
        String actualText = actual == null ? "" : String.valueOf(actual);
        String expectedText = expected == null ? "" : expected;
        if (!actualText.equals(expectedText)) {
            throw new IllegalArgumentException("cursor filter mismatch: " + field);
        }
    }

    private String safeCursor(String cursor) {
        return cursor == null ? "" : cursor;
    }

    private SecurityAuditEventDto parseAuditEvent(String line) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(line, new TypeReference<>() {});
            return new SecurityAuditEventDto(
                    parseInstant(parsed.get("timestamp")),
                    asText(parsed.get("traceId")),
                    asText(parsed.get("actor")),
                    asText(parsed.get("operation")),
                    asText(parsed.get("resource")),
                    asText(parsed.get("result")),
                    asText(parsed.get("reason")),
                    asText(parsed.get("remoteAddress")),
                    asText(parsed.get("userAgent"))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matchesFilters(SecurityAuditEventDto event,
                                   String actor,
                                   String operation,
                                   String result,
                                   String traceId,
                                   Instant from,
                                   Instant to) {
        return containsIgnoreCase(event.actor(), actor)
                && containsIgnoreCase(event.operation(), operation)
                && containsIgnoreCase(event.result(), result)
                && containsIgnoreCase(event.traceId(), traceId)
                && withinWindow(event.timestamp(), from, to);
    }

    private boolean withinWindow(Instant timestamp, Instant from, Instant to) {
        Instant ts = timestamp == null ? Instant.EPOCH : timestamp;
        if (from != null && ts.isBefore(from)) {
            return false;
        }
        if (to != null && ts.isAfter(to)) {
            return false;
        }
        return true;
    }

    private boolean containsIgnoreCase(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private Instant parseOptionalInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid time format: " + value);
        }
    }
}

