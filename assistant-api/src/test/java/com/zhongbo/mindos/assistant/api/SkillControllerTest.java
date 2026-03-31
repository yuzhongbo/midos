package com.zhongbo.mindos.assistant.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldListRegisteredSkills() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("echo")))
                .andExpect(jsonPath("$[*].name", hasItem("time")));
    }

    @Test
    void shouldReloadCustomSkills() throws Exception {
        mockMvc.perform(post("/api/skills/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.reloaded").isNumber());
    }

    @Test
    void shouldRejectLoadJarRequestWithoutUrl() throws Exception {
        mockMvc.perform(post("/api/skills/load-jar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Field 'url' is required."));
    }

    @Test
    void shouldReloadMcpSkills() throws Exception {
        mockMvc.perform(post("/api/skills/reload-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.reloaded").isNumber());
    }

    @Test
    void shouldReloadCloudApiSkills() throws Exception {
        mockMvc.perform(post("/api/skills/reload-cloud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.reloaded").isNumber());
    }

    @Test
    void shouldRejectLoadMcpRequestWithoutAlias() throws Exception {
        mockMvc.perform(post("/api/skills/load-mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:8081/mcp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Field 'alias' is required."));
    }

    @Test
    void shouldRejectLoadMcpRequestWithoutUrl() throws Exception {
        mockMvc.perform(post("/api/skills/load-mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"docs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Field 'url' is required."));
    }

    @Test
    void shouldLoadMcpServerWithHeaders() throws Exception {
        HttpServer mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/mcp", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            Map<String, Object> request = objectMapper.readValue(
                    exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            int statusCode = "Bearer api-token".equals(auth) ? 200 : 401;
            byte[] response = switch (method) {
                case "initialize" -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
                case "tools/list" -> json(Map.of(
                        "jsonrpc", "2.0",
                        "id", request.get("id"),
                        "result", Map.of("tools", List.of(Map.of("name", "docs", "description", "Docs")))
                ));
                default -> json(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of()));
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            mcpServer.start();
            String url = "http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp";
            mockMvc.perform(post("/api/skills/load-mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"alias\":\"docs\",\"url\":\"" + url + "\",\"headers\":{\"Authorization\":\"Bearer api-token\"}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.headersApplied").value(1))
                    .andExpect(jsonPath("$.loaded").value(1));
        } finally {
            mcpServer.stop(0);
        }
    }

    private byte[] json(Map<String, Object> value) throws java.io.IOException {
        return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }
}

