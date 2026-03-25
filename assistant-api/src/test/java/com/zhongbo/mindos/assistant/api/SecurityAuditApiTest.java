package com.zhongbo.mindos.assistant.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.security.risky-ops.require-approval=true",
        "mindos.security.risky-ops.use-challenge-token=true",
        "mindos.security.risky-ops.admin-token-header=X-MindOS-Admin-Token",
        "mindos.security.risky-ops.admin-token=test-admin-token",
        "mindos.security.audit.enabled=true",
        "mindos.security.audit.file=target/security-audit-test.log",
        "mindos.security.audit.cursor-active-key-version=v2",
        "mindos.security.audit.cursor-signing-keys=v1:legacy-secret,v2:active-secret"
})
@AutoConfigureMockMvc
class SecurityAuditApiTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanAuditFile() throws Exception {
        Files.deleteIfExists(Path.of("target/security-audit-test.log"));
        try (Stream<Path> stream = Files.list(Path.of("target"))) {
            stream.filter(path -> path.getFileName().toString().startsWith("security-audit-test-"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // best effort cleanup for test isolation
                        }
                    });
        }
    }

    @Test
    void shouldReturnRecentSecurityAuditEventsWithTraceId() throws Exception {
        String challengeResponse = mockMvc.perform(post("/api/security/challenge")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .header("X-Trace-Id", "trace-audit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"tasks.auto-run\",\"resource\":\"audit-user\",\"actor\":\"audit-user\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(challengeResponse, "$.token");

        mockMvc.perform(post("/api/tasks/audit-user/auto-run")
                        .header("X-Trace-Id", "trace-audit-1")
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/security/audit")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").isString())
                .andExpect(jsonPath("$[0].operation").isString())
                .andExpect(jsonPath("$[0].resource").isString())
                .andExpect(jsonPath("$[0].result").isString());

        mockMvc.perform(get("/api/security/audit/write-metrics")
                        .header("X-MindOS-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueDepth").isNumber())
                .andExpect(jsonPath("$.queueRemainingCapacity").isNumber())
                .andExpect(jsonPath("$.enqueuedCount").isNumber())
                .andExpect(jsonPath("$.writtenCount").isNumber())
                .andExpect(jsonPath("$.callerRunsFallbackCount").isNumber())
                .andExpect(jsonPath("$.flushTimeoutCount").isNumber())
                .andExpect(jsonPath("$.flushErrorCount").isNumber())
                .andExpect(jsonPath("$.enqueuedCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void shouldSupportFilteredAuditQueryWithCursorPaging() throws Exception {
        mockMvc.perform(post("/api/security/challenge")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .header("X-Trace-Id", "trace-page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"tasks.auto-run\",\"resource\":\"audit-user\",\"actor\":\"audit-user\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/security/challenge")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .header("X-Trace-Id", "trace-page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"skills.load-mcp\",\"resource\":\"docs@http://localhost/mcp\",\"actor\":\"system\"}"))
                .andExpect(status().isOk());

        String page1 = mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "1")
                        .param("traceId", "trace-page")
                        .param("result", "allowed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].traceId").value("trace-page"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andExpect(jsonPath("$.nextCursorExpiresAt").isString())
                .andExpect(jsonPath("$.cursorKeyVersion").value("v2"))
                .andExpect(jsonPath("$.cursorType").value("none"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String nextCursor = JsonPath.read(page1, "$.nextCursor");
        String[] cursorParts = nextCursor.split("\\.");
        org.junit.jupiter.api.Assertions.assertEquals(3, cursorParts.length);
        String headerJson = new String(Base64.getUrlDecoder().decode(cursorParts[0]), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(headerJson.contains("\"kid\":\"v2\""));

        mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "1")
                        .param("cursor", nextCursor)
                        .param("traceId", "trace-page")
                        .param("result", "allowed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.cursor").value(nextCursor))
                .andExpect(jsonPath("$.cursorKeyVersion").value("v2"))
                .andExpect(jsonPath("$.cursorType").value("jwt"));

        mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "1")
                        .param("cursor", "0")
                        .param("traceId", "trace-page")
                        .param("result", "allowed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cursorKeyVersion").value("legacy-numeric"))
                .andExpect(jsonPath("$.cursorType").value("legacy-numeric"));

        String legacyCursor = createLegacySignatureCursor(0, "allowed", "trace-page");
        mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "1")
                        .param("cursor", legacyCursor)
                        .param("traceId", "trace-page")
                        .param("result", "allowed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cursor").value(legacyCursor))
                .andExpect(jsonPath("$.cursorKeyVersion").value("legacy-signature"))
                .andExpect(jsonPath("$.cursorType").value("legacy-signature"));

        mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "1")
                        .param("cursor", nextCursor + "tampered")
                        .param("traceId", "trace-page")
                        .param("result", "allowed"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "1")
                        .param("cursor", nextCursor)
                        .param("traceId", "trace-page-2")
                        .param("result", "allowed"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/security/audit/query")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "5")
                        .param("traceId", "trace-page")
                        .param("result", "allowed")
                        .param("from", "2999-01-01T00:00:00Z")
                        .param("to", "2999-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.from").value("2999-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2999-01-02T00:00:00Z"));
    }

    private String createLegacySignatureCursor(int offset, String result, String traceId) throws Exception {
        String payloadJson = "{\"offset\":" + offset
                + ",\"result\":\"" + result + "\""
                + ",\"traceId\":\"" + traceId + "\"}";
        String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("active-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payloadEncoded.getBytes(StandardCharsets.UTF_8)));
        return payloadEncoded + "." + signature;
    }
}

