package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
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
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "webSearch", "Search latest web news");

        assertTrue(executor.supports(tool, "今天新闻"));
        assertTrue(executor.routingScore(tool, "今天新闻") > 0);
    }

    @Test
    void shouldPreferDocumentationSearchIntentForDocsTool() {
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("docs", "http://unused.local/mcp", "searchDocs", "Search product documentation and guides");

        assertTrue(executor.supports(tool, "search docs for auth guide"));
        assertTrue(executor.routingScore(tool, "search docs for auth guide") > executor.routingScore(tool, "今天新闻"));
    }

    @Test
    void shouldFillQueryFromInputForSearchToolWhenMissing() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");

        executor.execute(tool, client, new SkillContext("u1", "查看今天新闻 股市", Map.of()));

        assertEquals("股市", client.lastArguments.get("query"));
    }

    @Test
    void shouldNotOverrideExplicitQueryForSearchTool() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");

        executor.execute(tool, client, new SkillContext("u1", "查看今天新闻 股市", Map.of("query", "股市")));

        assertEquals("股市", client.lastArguments.get("query"));
    }

    @Test
    void shouldExtractTopicFromNaturalLanguageNewsRequest() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");

        executor.execute(tool, client, new SkillContext("u1", "帮我看看科技新闻", Map.of()));

        assertEquals("科技", client.lastArguments.get("query"));
    }

    @Test
    void shouldReplyNaturallyWhenSearchThemeIsMissing() {
        CapturingMcpClient client = new CapturingMcpClient();
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("qwensearch", "http://unused.local/mcp", "bailian_web_search", "Search latest web news");

        var result = executor.execute(tool, client, new SkillContext("u1", "帮我查一下", Map.of()));

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
            McpToolExecutor executor = new McpToolExecutor();
            McpToolDefinition tool = new McpToolDefinition("bravesearch", "http://unused.local/res/v1/web/search", "webSearch", "Search latest web news");

            var result = executor.execute(tool, new FailingMcpClient(), new SkillContext("u1", "国际实时新闻", Map.of("query", "国际实时新闻")));

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
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("bravesearch", "http://unused.local/res/v1/web/search", "webSearch", "Search latest web news");

        var result = executor.execute(tool, new BraveChallengeMcpClient(), new SkillContext("u1", "国际实时新闻", Map.of("query", "国际实时新闻")));

        assertTrue(!result.success());
        assertTrue(result.output().contains("HTTP 403"));
        assertTrue(result.output().contains("X-Subscription-Token"));
        assertTrue(!result.output().contains("<!DOCTYPE html>"));
    }

    @Test
    void shouldReturnFriendlyReplyWhenSearchConnectionIsReset() {
        McpToolExecutor executor = new McpToolExecutor();
        McpToolDefinition tool = new McpToolDefinition("bravesearch", "http://unused.local/res/v1/web/search", "webSearch", "Search latest web news");

        var result = executor.execute(tool, new ConnectionResetMcpClient(), new SkillContext("u1", "国际实时新闻", Map.of("query", "国际实时新闻")));

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
}
