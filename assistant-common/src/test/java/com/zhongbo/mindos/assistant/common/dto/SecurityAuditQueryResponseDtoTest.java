package com.zhongbo.mindos.assistant.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class SecurityAuditQueryResponseDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void shouldSerializeAndDeserializeCursorMetadata() throws Exception {
        SecurityAuditQueryResponseDto response = new SecurityAuditQueryResponseDto(
                List.of(new SecurityAuditEventDto(
                        Instant.parse("2026-03-23T12:00:00Z"),
                        "trace-1",
                        "audit-reader",
                        "security.audit.query",
                        "security-audit",
                        "allowed",
                        "ok",
                        "127.0.0.1",
                        "JUnit"
                )),
                50,
                "cursor-1",
                "cursor-2",
                "2026-03-23T12:05:00Z",
                "v2",
                "jwt",
                "audit-reader",
                "security.audit.query",
                "allowed",
                "trace-1",
                "2026-03-23T11:00:00Z",
                "2026-03-23T12:00:00Z"
        );

        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"cursorKeyVersion\":\"v2\""));
        assertTrue(json.contains("\"cursorType\":\"jwt\""));

        SecurityAuditQueryResponseDto restored = objectMapper.readValue(json, SecurityAuditQueryResponseDto.class);
        assertEquals(restored.cursorKeyVersion(), "v2");
        assertEquals(restored.cursorType(), "jwt");
        assertEquals(restored.items().size(), 1);
        assertEquals(restored.items().get(0).traceId(), "trace-1");
        assertEquals(restored.items().get(0).timestamp(), Instant.parse("2026-03-23T12:00:00Z"));
    }
}

