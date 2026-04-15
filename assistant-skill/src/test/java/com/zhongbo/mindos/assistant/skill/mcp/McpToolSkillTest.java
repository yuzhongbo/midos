package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.SkillRoutingProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolSkillTest {

    @Test
    void shouldMatchChineseRealtimeNewsIntentForWebSearchTool() {
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "webSearch", "Search latest web news");
        DefaultSkillCatalog catalog = catalogWith(tool, new CapturingMcpClient());

        assertEquals(tool.skillName(), catalog.detectSkillName("今天新闻").orElse(null));
        assertEquals(tool.skillName(), catalog.detectSkillCandidates("今天新闻", 1).get(0).skillName());
    }

    @Test
    void shouldPreferDocumentationSearchIntentForDocsTool() {
        McpToolDefinition tool = new McpToolDefinition("docs", "http://unused.local/mcp", "searchDocs", "Search product documentation and guides");
        DefaultSkillCatalog catalog = catalogWith(tool, new CapturingMcpClient());

        assertEquals(tool.skillName(), catalog.detectSkillName("search docs for auth guide").orElse(null));
        assertTrue(catalog.detectSkillName("今天新闻").isEmpty());
    }

    @Test
    void shouldAcceptKeywordAliasForSearchTool() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");
        McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, client));

        executor.execute(tool.skillName(), Map.of("keyword", "股市"));

        assertEquals("股市", client.lastArguments.get("query"));
    }

    @Test
    void shouldNotOverrideExplicitQueryForSearchTool() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");
        McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, client));

        executor.execute(tool.skillName(), Map.of("query", "股市", "input", "查看今天新闻 股市"));

        assertEquals("股市", client.lastArguments.get("query"));
    }

    @Test
    void shouldReplyNaturallyWhenSearchQueryIsMissingEvenForNaturalLanguageInput() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");
        McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, client));

        var result = executor.execute(tool.skillName(), Map.of("input", "帮我看看科技新闻"));

        assertTrue(result.success());
        assertTrue(result.output().contains("抱歉"));
        assertTrue(client.lastArguments.isEmpty());
    }

    @Test
    void shouldReplyNaturallyWhenSearchThemeIsMissing() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");
        McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, client));

        var result = executor.execute(tool.skillName(), Map.of("input", "帮我查一下"));

        assertTrue(result.success());
        assertTrue(result.output().contains("抱歉"));
        assertTrue(result.output().contains("科技新闻"));
        assertTrue(client.lastArguments.isEmpty());
    }

    @Test
    void shouldLogStructuredFailureWhenMcpCallThrows() {
        Logger logger = Logger.getLogger(McpToolExecutor.class.getName());
        CapturingHandler handler = new CapturingHandler();
        Level previousLevel = logger.getLevel();
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        try {
            McpToolDefinition tool = new McpToolDefinition("bravesearch", "http://unused.local/res/v1/web/search", "webSearch", "Search latest web news");
            McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, new FailingMcpClient()));

            var result = executor.execute(tool.skillName(), Map.of("query", "国际实时新闻", "input", "国际实时新闻"));

            assertTrue(!result.success());
            assertTrue(result.output().contains("网络不稳定"));
            assertTrue(result.output().contains("自动重试"));
            String logs = String.join("\n", handler.messages);
            assertTrue(logs.contains("mcp.tool.failure"));
            assertTrue(logs.contains("\"skill\":\"mcp.bravesearch.webSearch\""));
            assertTrue(logs.contains("\"tool\":\"webSearch\""));
            assertTrue(logs.contains("\"serverAlias\":\"bravesearch\""));
            assertTrue(logs.contains("socket timeout"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParentHandlers);
        }
    }

    @Test
    void shouldReturnFriendlyReplyWhenBraveSearchIsBlockedBy403Challenge() {
        McpToolDefinition tool = new McpToolDefinition("bravesearch", "http://unused.local/res/v1/web/search", "webSearch", "Search latest web news");
        McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, new BraveChallengeMcpClient()));

        var result = executor.execute(tool.skillName(), Map.of("query", "国际实时新闻", "input", "国际实时新闻"));

        assertTrue(!result.success());
        assertTrue(result.output().contains("HTTP 403"));
        assertTrue(result.output().contains("X-Subscription-Token"));
        assertTrue(!result.output().contains("<!DOCTYPE html>"));
    }

    @Test
    void shouldReturnFriendlyReplyWhenSearchConnectionIsReset() {
        McpToolDefinition tool = new McpToolDefinition("bravesearch", "http://unused.local/res/v1/web/search", "webSearch", "Search latest web news");
        McpToolExecutor executor = new McpToolExecutor(toolCatalogWith(tool, new ConnectionResetMcpClient()));

        var result = executor.execute(tool.skillName(), Map.of("query", "国际实时新闻", "input", "国际实时新闻"));

        assertTrue(!result.success());
        assertTrue(result.output().contains("网络不稳定"));
        assertTrue(result.output().contains("自动重试"));
        assertTrue(!result.output().contains("MCP tool call failed: Connection reset"));
    }

    private static final class CapturingMcpClient extends McpJsonRpcClient {
        private Map<String, Object> lastArguments = Map.of();

        @Override
        public String callTool(String serverUrl, String toolName, Map<String, Object> arguments, Map<String, String> headers) {
            this.lastArguments = new LinkedHashMap<>(arguments);
            return "ok";
        }
    }

    private static final class FailingMcpClient extends McpJsonRpcClient {
        @Override
        public String callTool(String serverUrl, String toolName, Map<String, Object> arguments, Map<String, String> headers) {
            throw new IllegalStateException("socket timeout");
        }
    }

    private static final class BraveChallengeMcpClient extends McpJsonRpcClient {
        @Override
        public String callTool(String serverUrl, String toolName, Map<String, Object> arguments, Map<String, String> headers) {
            throw new IllegalStateException("Brave search returned HTTP 403 (blocked by upstream challenge). Please verify Brave key/header and source IP allowlist, then retry.");
        }
    }

    private static final class ConnectionResetMcpClient extends McpJsonRpcClient {
        @Override
        public String callTool(String serverUrl, String toolName, Map<String, Object> arguments, Map<String, String> headers) {
            throw new IllegalStateException("Connection reset");
        }
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static DefaultSkillCatalog catalogWith(McpToolDefinition tool, McpJsonRpcClient client) {
        return new DefaultSkillCatalog(new SkillRegistry(List.of()), toolCatalogWith(tool, client), new SkillRoutingProperties());
    }

    private static DefaultMcpToolCatalog toolCatalogWith(McpToolDefinition tool, McpJsonRpcClient client) {
        DefaultMcpToolCatalog toolCatalog = new DefaultMcpToolCatalog();
        toolCatalog.register(tool, client);
        return toolCatalog;
    }
}
