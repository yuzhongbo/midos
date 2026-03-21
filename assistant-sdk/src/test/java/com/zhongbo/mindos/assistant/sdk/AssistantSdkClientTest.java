package com.zhongbo.mindos.assistant.sdk;

import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileDto;
import com.zhongbo.mindos.assistant.common.dto.PersonaProfileExplainDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSdkClientTest {

    @Test
    void shouldFetchConversationHistoryWithEncodedUserId() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            byte[] response = ("[" +
                    "{\"role\":\"user\",\"content\":\"hello\",\"createdAt\":\"2026-03-13T00:00:00Z\"}," +
                    "{\"role\":\"assistant\",\"content\":\"hi\",\"createdAt\":\"2026-03-13T00:00:01Z\"}" +
                    "]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            List<ConversationTurnDto> history = client.fetchConversationHistory("user a+b");

            assertEquals(2, history.size());
            assertTrue(pathRef.get().contains("/api/chat/user+a%2Bb/history"));
            assertEquals("user", history.get(0).role());
            assertEquals("hello", history.get(0).content());
            assertEquals(Instant.parse("2026-03-13T00:00:00Z"), history.get(0).createdAt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchMemorySyncWithEncodedUserId() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            byte[] response = ("{" +
                    "\"cursor\":7," +
                    "\"acceptedCount\":0," +
                    "\"skippedCount\":0," +
                    "\"episodic\":[]," +
                    "\"semantic\":[]," +
                    "\"procedural\":[]" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var response = client.fetchMemorySync("user a+b", 3, 20);

            assertEquals(7L, response.cursor());
            assertTrue(pathRef.get().contains("/api/memory/user+a%2Bb/sync"));
            assertTrue(pathRef.get().contains("since=3"));
            assertTrue(pathRef.get().contains("limit=20"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchPersonaProfileWithEncodedUserId() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            byte[] response = ("{" +
                    "\"assistantName\":\"MindOS\"," +
                    "\"role\":\"高一\"," +
                    "\"style\":\"练习优先\"," +
                    "\"language\":\"zh-CN\"," +
                    "\"timezone\":\"Asia/Shanghai\"," +
                    "\"preferredChannel\":\"teaching.plan\"" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            PersonaProfileDto response = client.getPersonaProfile("user a+b");

            assertEquals("MindOS", response.assistantName());
            assertEquals("高一", response.role());
            assertEquals("teaching.plan", response.preferredChannel());
            assertTrue(pathRef.get().contains("/api/memory/user+a%2Bb/persona"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchPersonaExplainWithEncodedUserId() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            byte[] response = ("{" +
                    "\"confirmed\":{" +
                    "\"assistantName\":\"MindOS\"," +
                    "\"role\":\"高一\"," +
                    "\"style\":\"练习优先\"," +
                    "\"language\":\"zh-CN\"," +
                    "\"timezone\":\"Asia/Shanghai\"," +
                    "\"preferredChannel\":\"teaching.plan\"}," +
                    "\"pendingOverrides\":[{" +
                    "\"field\":\"role\"," +
                    "\"pendingValue\":\"程序员\"," +
                    "\"count\":1," +
                    "\"confirmThreshold\":2," +
                    "\"remainingConfirmTurns\":1" +
                    "}]" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            PersonaProfileExplainDto response = client.getPersonaProfileExplain("user a+b");

            assertEquals("高一", response.confirmed().role());
            assertEquals(1, response.pendingOverrides().size());
            assertEquals("role", response.pendingOverrides().get(0).field());
            assertTrue(pathRef.get().contains("/api/memory/user+a%2Bb/persona/explain"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldApplyMemorySyncAndParseStructuredError() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory", exchange -> {
            byte[] response = "{\"code\":\"INVALID_MEMORY_SYNC\",\"message\":\"Broken payload\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            try {
                client.applyMemorySync(
                        "sdk-user",
                        new MemorySyncRequestDto("evt-1", List.of(), List.of(), List.of()),
                        50
                );
            } catch (AssistantSdkException ex) {
                assertEquals(400, ex.statusCode());
                assertEquals("INVALID_MEMORY_SYNC", ex.errorCode());
                assertEquals("Broken payload", ex.getMessage());
                return;
            }
        } finally {
            server.stop(0);
        }

        throw new AssertionError("Expected AssistantSdkException to be thrown");
    }

    @Test
    void shouldFetchUpdateStyleAndBuildCompressionPlan() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/memory", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response;
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && pathRef.get().contains("/style")) {
                response = "{\"styleName\":\"action\",\"tone\":\"warm\",\"outputFormat\":\"bullet\"}"
                        .getBytes(StandardCharsets.UTF_8);
            } else if (pathRef.get().contains("/style")) {
                response = bodyRef.get().getBytes(StandardCharsets.UTF_8);
            } else {
                response = ("{" +
                        "\"style\":{\"styleName\":\"action\",\"tone\":\"warm\",\"outputFormat\":\"bullet\"}," +
                        "\"steps\":[{" +
                        "\"stage\":\"RAW\",\"content\":\"原文\",\"length\":2},{" +
                        "\"stage\":\"CONDENSED\",\"content\":\"压缩\",\"length\":2}]," +
                        "\"createdAt\":\"2026-03-17T00:00:00Z\"" +
                        "}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            MemoryStyleProfileDto style = client.getMemoryStyle("user a+b");
            assertEquals("action", style.styleName());
            assertTrue(pathRef.get().contains("/api/memory/user+a%2Bb/style"));

            MemoryStyleProfileDto updated = client.updateMemoryStyle("user a+b",
                    new MemoryStyleProfileDto("coach", "calm", "plain"));
            assertEquals("coach", updated.styleName());
            assertTrue(bodyRef.get().contains("\"styleName\":\"coach\""));
            assertTrue(pathRef.get().contains("autoTune=false"));

            client.updateMemoryStyle("user a+b",
                    new MemoryStyleProfileDto("coach", "calm", "plain"),
                    true,
                    "请帮我按步骤拆分任务清单");
            assertTrue(pathRef.get().contains("autoTune=true"));
            assertTrue(pathRef.get().contains("sampleText="));

            var plan = client.buildMemoryCompressionPlan("user a+b",
                    new MemoryCompressionPlanRequestDto("原始记忆", null, null, null, "task"));
            assertTrue(pathRef.get().contains("/api/memory/user+a%2Bb/compress-plan"));
            assertEquals("action", plan.style().styleName());
            assertEquals(2, plan.steps().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldListSkills() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            byte[] response = ("[" +
                    "{\"name\":\"echo\",\"description\":\"Echo skill\"}," +
                    "{\"name\":\"mcp.docs.searchDocs\",\"description\":\"Search docs\"}" +
                    "]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            List<java.util.Map<String, String>> skills = client.listSkills();

            assertEquals(2, skills.size());
            assertEquals("/api/skills", pathRef.get());
            assertEquals("echo", skills.get(0).get("name"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLoadMcpServer() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{" +
                    "\"loaded\":2," +
                    "\"alias\":\"docs\"," +
                    "\"url\":\"http://localhost:8081/mcp\"," +
                    "\"status\":\"ok\"" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            java.util.Map<String, Object> response = client.loadMcpServer("docs", "http://localhost:8081/mcp");

            assertEquals("/api/skills/load-mcp", pathRef.get());
            assertTrue(bodyRef.get().contains("\"alias\":\"docs\""));
            assertTrue(bodyRef.get().contains("\"url\":\"http://localhost:8081/mcp\""));
            assertEquals(2, response.get("loaded"));
            assertEquals("ok", response.get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLoadMcpServerWithHeaders() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{" +
                    "\"loaded\":1," +
                    "\"alias\":\"github\"," +
                    "\"url\":\"https://example.com/mcp\"," +
                    "\"headersApplied\":1," +
                    "\"status\":\"ok\"" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            java.util.Map<String, Object> response = client.loadMcpServer(
                    "github",
                    "https://example.com/mcp",
                    java.util.Map.of("Authorization", "Bearer token")
            );

            assertEquals("/api/skills/load-mcp", pathRef.get());
            assertTrue(bodyRef.get().contains("\"alias\":\"github\""));
            assertTrue(bodyRef.get().contains("\"headers\":{"));
            assertTrue(bodyRef.get().contains("\"Authorization\":\"Bearer token\""));
            assertEquals(1, response.get("loaded"));
            assertEquals("ok", response.get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLoadExternalJar() throws IOException {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills", exchange -> {
            pathRef.set(exchange.getRequestURI().toString());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{" +
                    "\"loaded\":1," +
                    "\"url\":\"https://example.com/skill-weather.jar\"," +
                    "\"status\":\"ok\"" +
                    "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            server.start();
            AssistantSdkClient client = new AssistantSdkClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            java.util.Map<String, Object> response = client.loadExternalJar("https://example.com/skill-weather.jar");

            assertEquals("/api/skills/load-jar", pathRef.get());
            assertTrue(bodyRef.get().contains("\"url\":\"https://example.com/skill-weather.jar\""));
            assertEquals(1, response.get("loaded"));
            assertEquals("ok", response.get("status"));
        } finally {
            server.stop(0);
        }
    }
}

