package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class McpToolSkill implements Skill {

    private static final Logger LOGGER = Logger.getLogger(McpToolSkill.class.getName());

    private static final List<String> SEARCH_INTENT_CUES = List.of(
            "search", "find", "lookup", "query", "搜", "搜索", "查询", "查一下", "查找", "帮我查"
    );
    private static final List<String> DOC_INTENT_CUES = List.of(
            "docs", "doc", "documentation", "manual", "guide", "文档", "手册", "指南", "说明"
    );
    private static final List<String> REALTIME_INTENT_CUES = List.of(
            "news", "latest", "realtime", "real time", "current", "today", "headline",
            "新闻", "最新", "实时", "今天", "头条", "热点", "热搜", "天气", "汇率", "股价"
    );
    private static final Pattern LEADING_SEARCH_REQUEST_PATTERN = Pattern.compile(
            "^(?:请|请帮我|帮我|麻烦你|想|我想|我想看|我想看看|我想了解|想看|想了解|查看|看|看看|查|查下|查一下|搜|搜下|搜一下|搜索|了解一下|了解|告诉我)?\\s*"
                    + "(?:一下|一下子)?\\s*(?:今天|今日|最新|实时|当前)?\\s*(?:的)?\\s*(?:新闻|资讯|消息|头条|热点|热搜)?\\s*(?:关于|有关|里|中)?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_NEWS_PATTERN = Pattern.compile("(?:的)?\\s*(?:新闻|资讯|消息|头条|热点|热搜)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_CONNECTOR_PATTERN = Pattern.compile("^(?:关于|有关|里|中的|中|的)\\s*");
    private static final Pattern ONLY_GENERIC_NEWS_PATTERN = Pattern.compile(
            "^(?:今天|今日|最新|实时|当前|新闻|资讯|消息|头条|热点|热搜|看看|查看|查一下|搜索|搜一下|搜|查|看|想看|我想看|我想了解|了解一下|关于|有关|的|\\s)+$",
            Pattern.CASE_INSENSITIVE);

    private final McpToolDefinition toolDefinition;
    private final McpJsonRpcClient mcpClient;

    public McpToolSkill(McpToolDefinition toolDefinition, McpJsonRpcClient mcpClient) {
        this.toolDefinition = toolDefinition;
        this.mcpClient = mcpClient;
    }

    @Override
    public String name() {
        return toolDefinition.skillName();
    }

    @Override
    public String description() {
        return toolDefinition.description();
    }

    @Override
    public List<String> routingKeywords() {
        List<String> keywords = new ArrayList<>();
        keywords.add(toolDefinition.serverAlias());
        keywords.add(splitCamelCase(toolDefinition.name()));
        keywords.add((toolDefinition.serverAlias() + " " + splitCamelCase(toolDefinition.name())).trim());
        if (toolDefinition.description() != null && !toolDefinition.description().isBlank()) {
            keywords.add(toolDefinition.description());
        }
        String capabilityText = normalizePhrase(String.join(" ", keywords));
        if (containsAny(capabilityText, SEARCH_INTENT_CUES)) {
            keywords.addAll(SEARCH_INTENT_CUES);
        }
        if (containsAny(capabilityText, DOC_INTENT_CUES)) {
            keywords.addAll(DOC_INTENT_CUES);
        }
        if (containsAny(capabilityText, REALTIME_INTENT_CUES)
                || containsAny(capabilityText, List.of("websearch", "web search", "searchweb", "internet", "网页", "联网"))) {
            keywords.addAll(REALTIME_INTENT_CUES);
            keywords.addAll(List.of("web search", "websearch", "internet", "联网", "网页"));
        }
        return List.copyOf(keywords);
    }

    @Override
    public boolean supports(String input) {
        return routingScore(input) > 0;
    }

    @Override
    public int routingScore(String input) {
        if (input == null || input.isBlank()) {
            return Integer.MIN_VALUE;
        }

        String normalizedInput = normalizePhrase(input);
        String normalizedSkillName = normalizePhrase(name());
        String normalizedToolName = normalizePhrase(toolDefinition.name());
        if (normalizedInput.equals(normalizedSkillName)
                || normalizedInput.startsWith(normalizedSkillName + " ")) {
            return 1000;
        }
        if (!normalizedToolName.isBlank() && (normalizedInput.equals(normalizedToolName)
                || normalizedInput.startsWith(normalizedToolName + " "))) {
            return 950;
        }

        List<String> phrases = new ArrayList<>(routingKeywords());
        phrases.add(0, name());

        int bestScore = Integer.MIN_VALUE;
        for (String phrase : phrases) {
            String normalizedPhrase = normalizePhrase(phrase);
            if (normalizedPhrase.isBlank()) {
                continue;
            }
            if (normalizedInput.contains(normalizedPhrase)) {
                bestScore = Math.max(bestScore, 700 + normalizedPhrase.length());
                continue;
            }
            int overlap = countMatchedSignificantWords(normalizedInput, normalizedPhrase);
            if (overlap > 0) {
                bestScore = Math.max(bestScore, 300 + overlap * 40);
            }
        }

        String capabilityText = normalizePhrase(String.join(" ", phrases));
        boolean docsTool = containsAny(capabilityText, DOC_INTENT_CUES)
                && containsAny(capabilityText, SEARCH_INTENT_CUES);
        boolean realtimeSearchTool = containsAny(capabilityText, REALTIME_INTENT_CUES)
                || containsAny(capabilityText, List.of("websearch", "web search", "searchweb", "internet", "网页", "联网"));
        boolean generalSearchTool = realtimeSearchTool || containsAny(capabilityText, SEARCH_INTENT_CUES);

        if (docsTool && containsAny(normalizedInput, DOC_INTENT_CUES)) {
            bestScore = Math.max(bestScore, 520 + countCueMatches(normalizedInput, DOC_INTENT_CUES) * 20);
        }
        if (generalSearchTool && containsAny(normalizedInput, SEARCH_INTENT_CUES)) {
            bestScore = Math.max(bestScore, 420 + countCueMatches(normalizedInput, SEARCH_INTENT_CUES) * 20);
        }
        if (realtimeSearchTool && containsAny(normalizedInput, REALTIME_INTENT_CUES)) {
            bestScore = Math.max(bestScore, 560 + countCueMatches(normalizedInput, REALTIME_INTENT_CUES) * 20);
        }

        return bestScore > 0 ? bestScore : Integer.MIN_VALUE;
    }

    @Override
    public SkillResult run(SkillContext context) {
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(context.attributes());
            if (!arguments.containsKey("input") && context.input() != null && !context.input().isBlank()) {
                arguments.put("input", context.input());
            }
            ensureSearchQuery(arguments, context);
            if (isSearchLikeTool() && stringValue(arguments.get("query")).isBlank()) {
                return SkillResult.success(name(), buildMissingQueryReply(context));
            }
            String output = mcpClient.callTool(
                    toolDefinition.serverUrl(),
                    toolDefinition.name(),
                    arguments,
                    toolDefinition.headers()
            );
            return SkillResult.success(name(), output);
        } catch (RuntimeException ex) {
            if (isMissingQueryError(ex)) {
                return SkillResult.success(name(), buildMissingQueryReply(context));
            }
            String failureMessage = rootCauseMessage(ex);
            LOGGER.warning(() -> "{\"event\":\"mcp.tool.failure\",\"skill\":\""
                    + escapeJson(name())
                    + "\",\"tool\":\""
                    + escapeJson(toolDefinition.name())
                    + "\",\"serverAlias\":\""
                    + escapeJson(toolDefinition.serverAlias())
                    + "\",\"message\":\""
                    + escapeJson(failureMessage)
                    + "\"}");
            return SkillResult.failure(name(), buildFriendlyFailureReply(failureMessage));
        }
    }

    private String splitCamelCase(String value) {
        return value == null ? "" : value.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private String normalizePhrase(String value) {
        return value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private int countMatchedSignificantWords(String input, String phrase) {
        String[] words = phrase.split(" ");
        int matchedWords = 0;
        for (String word : words) {
            if (!isSignificantWord(word)) {
                continue;
            }
            if (input.contains(word)) {
                matchedWords++;
            }
        }
        return matchedWords;
    }

    private boolean isSignificantWord(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        long alnumCount = word.codePoints().filter(Character::isLetterOrDigit).count();
        return alnumCount >= 2;
    }

    private boolean containsAny(String text, List<String> cues) {
        if (text == null || text.isBlank() || cues == null || cues.isEmpty()) {
            return false;
        }
        for (String cue : cues) {
            String normalizedCue = normalizePhrase(cue);
            if (!normalizedCue.isBlank() && text.contains(normalizedCue)) {
                return true;
            }
        }
        return false;
    }

    private int countCueMatches(String text, List<String> cues) {
        int matches = 0;
        for (String cue : cues) {
            String normalizedCue = normalizePhrase(cue);
            if (!normalizedCue.isBlank() && text.contains(normalizedCue)) {
                matches++;
            }
        }
        return matches;
    }

    private void ensureSearchQuery(Map<String, Object> arguments, SkillContext context) {
        if (!isSearchLikeTool()) {
            return;
        }
        String query = stringValue(arguments.get("query"));
        if (!query.isBlank()) {
            return;
        }
        String fallback = firstNonBlank(
                stringValue(arguments.get("input")),
                stringValue(arguments.get("originalInput")),
                context.input()
        );
        fallback = deriveSearchQuery(fallback);
        if (!fallback.isBlank()) {
            arguments.put("query", fallback);
        }
    }

    private String deriveSearchQuery(String rawInput) {
        String candidate = stringValue(rawInput);
        if (candidate.isBlank()) {
            return "";
        }
        String cleaned = candidate;
        for (int i = 0; i < 3; i++) {
            String next = LEADING_SEARCH_REQUEST_PATTERN.matcher(cleaned).replaceFirst("").trim();
            next = LEADING_CONNECTOR_PATTERN.matcher(next).replaceFirst("").trim();
            next = TRAILING_NEWS_PATTERN.matcher(next).replaceFirst("").trim();
            next = LEADING_CONNECTOR_PATTERN.matcher(next).replaceFirst("").trim();
            if (next.equals(cleaned)) {
                break;
            }
            cleaned = next;
        }
        if (cleaned.isBlank() || ONLY_GENERIC_NEWS_PATTERN.matcher(cleaned).matches()) {
            return deriveGenericSearchQuery(candidate);
        }
        return cleaned;
    }

    private String deriveGenericSearchQuery(String rawInput) {
        String normalized = normalizePhrase(rawInput);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("今天") || normalized.contains("今日") || normalized.contains("today")) {
            return "今天新闻";
        }
        if (normalized.contains("实时") || normalized.contains("latest") || normalized.contains("最新")) {
            return "最新新闻";
        }
        if (normalized.contains("头条") || normalized.contains("热点") || normalized.contains("热搜")) {
            return "热点新闻";
        }
        if (normalized.contains("news") || normalized.contains("新闻") || normalized.contains("资讯") || normalized.contains("消息")) {
            return "新闻";
        }
        return "";
    }

    private boolean isSearchLikeTool() {
        String capabilityText = normalizePhrase(name() + " " + description() + " " + splitCamelCase(toolDefinition.name()));
        return containsAny(capabilityText, SEARCH_INTENT_CUES)
                || containsAny(capabilityText, REALTIME_INTENT_CUES)
                || capabilityText.contains("websearch")
                || capabilityText.contains("web search");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String rootCauseMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = stringValue(current.getMessage());
        return message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String escapeJson(String value) {
        return stringValue(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private boolean isMissingQueryError(RuntimeException ex) {
        String message = ex == null ? "" : stringValue(ex.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("query is required")
                || message.contains("param:query is required")
                || message.contains("missing query");
    }

    private String buildMissingQueryReply(SkillContext context) {
        String originalInput = firstNonBlank(
                stringValue(context.attributes().get("originalInput")),
                context.input()
        );
        String attemptedTopic = deriveSearchQuery(originalInput);
        if (!attemptedTopic.isBlank()) {
            return "抱歉，刚才我没有顺利查到你提到的“" + attemptedTopic + "”相关新闻。你可以直接说想关注的方向，我会继续按这个主题帮你查，比如科技、AI、股市、上海或本地热点。";
        }
        return "抱歉，刚才没接住你想看的新闻方向。你直接用自然的话告诉我关注的主题就行，比如“我想看看今天的科技新闻”“帮我查一下 AI 动态”或“想了解今天股市有什么消息”。";
    }

    private String buildFriendlyFailureReply(String failureMessage) {
        String normalized = stringValue(failureMessage);
        String lowerCaseMessage = normalized.toLowerCase(Locale.ROOT);
        if (lowerCaseMessage.contains("brave search returned http 403")
                && (lowerCaseMessage.contains("upstream challenge")
                || lowerCaseMessage.contains("cloudflare")
                || lowerCaseMessage.contains("just a moment"))) {
            return "抱歉，Brave 搜索接口当前触发了访问拦截（HTTP 403）。我建议先重试一次；如果仍失败，请检查 Brave API Key、请求头 X-Subscription-Token，或切换到另一个搜索 MCP。";
        }
        if (lowerCaseMessage.contains("connection reset")
                || lowerCaseMessage.contains("socket timeout")
                || lowerCaseMessage.contains("timed out")
                || lowerCaseMessage.contains("connection refused")
                || lowerCaseMessage.contains("broken pipe")
                || lowerCaseMessage.contains("reset by peer")) {
            return "抱歉，联网搜索服务刚才网络不稳定，我已经自动重试过，但这次仍未成功。请稍后再试一次；如果持续出现，请检查 MCP 服务地址、反向代理/网络连通性，或切换到另一个搜索 MCP。";
        }
        return "MCP tool call failed: " + normalized;
    }
}

