package com.zhongbo.mindos.assistant.skill.mcp;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Component
public class McpToolExecutor {

    private static final Logger LOGGER = Logger.getLogger(McpToolExecutor.class.getName());

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
            "^(?:请|请帮我|帮我|帮我看看|麻烦你|想|我想|我想看|我想看看|我想了解|想看|想了解|查看|看|看看|查|查下|查一下|搜|搜下|搜一下|搜索|了解一下|了解|告诉我)?\\s*"
                    + "(?:一下|一下子)?\\s*(?:今天|今日|最新|实时|当前)?\\s*(?:的)?\\s*(?:新闻|资讯|消息|头条|热点|热搜)?\\s*(?:关于|有关|里|中)?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_NEWS_PATTERN = Pattern.compile("(?:的)?\\s*(?:新闻|资讯|消息|头条|热点|热搜)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_CONNECTOR_PATTERN = Pattern.compile("^(?:关于|有关|里|中的|中|的)\\s*");
    private static final Pattern ONLY_GENERIC_NEWS_PATTERN = Pattern.compile(
            "^(?:今天|今日|最新|实时|当前|新闻|资讯|消息|头条|热点|热搜|看看|查看|查一下|搜索|搜一下|搜|查|看|想看|我想看|我想了解|了解一下|关于|有关|的|\\s)+$",
            Pattern.CASE_INSENSITIVE);

    public List<String> routingKeywords(McpToolDefinition toolDefinition) {
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

    public boolean supports(McpToolDefinition toolDefinition, String input) {
        return routingScore(toolDefinition, input) > 0;
    }

    public int routingScore(McpToolDefinition toolDefinition, String input) {
        if (input == null || input.isBlank()) {
            return Integer.MIN_VALUE;
        }

        String normalizedInput = normalizePhrase(input);
        String normalizedSkillName = normalizePhrase(toolDefinition.skillName());
        String normalizedToolName = normalizePhrase(toolDefinition.name());
        if (normalizedInput.equals(normalizedSkillName)
                || normalizedInput.startsWith(normalizedSkillName + " ")) {
            return 1000;
        }
        if (!normalizedToolName.isBlank() && (normalizedInput.equals(normalizedToolName)
                || normalizedInput.startsWith(normalizedToolName + " "))) {
            return 950;
        }

        List<String> phrases = new ArrayList<>(routingKeywords(toolDefinition));
        phrases.add(0, toolDefinition.skillName());

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

    public SkillResult execute(McpToolDefinition toolDefinition,
                               McpJsonRpcClient mcpClient,
                               SkillContext context) {
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(context.attributes());
            if (!arguments.containsKey("input") && context.input() != null && !context.input().isBlank()) {
                arguments.put("input", context.input());
            }
            ensureSearchQuery(toolDefinition, arguments, context);
            if (isSearchLikeTool(toolDefinition) && stringValue(arguments.get("query")).isBlank()) {
                return SkillResult.success(toolDefinition.skillName(), buildMissingQueryReply(context));
            }
            String output = mcpClient.callTool(
                    toolDefinition.serverUrl(),
                    toolDefinition.name(),
                    arguments,
                    toolDefinition.headers()
            );
            return SkillResult.success(toolDefinition.skillName(), output);
        } catch (RuntimeException ex) {
            if (isMissingQueryError(ex)) {
                return SkillResult.success(toolDefinition.skillName(), buildMissingQueryReply(context));
            }
            String failureMessage = rootCauseMessage(ex);
            LOGGER.warning(() -> "{\"event\":\"mcp.tool.failure\",\"skill\":\""
                    + escapeJson(toolDefinition.skillName())
                    + "\",\"tool\":\""
                    + escapeJson(toolDefinition.name())
                    + "\",\"serverAlias\":\""
                    + escapeJson(toolDefinition.serverAlias())
                    + "\",\"message\":\""
                    + escapeJson(failureMessage)
                    + "\"}");
            return SkillResult.failure(toolDefinition.skillName(), buildFriendlyFailureReply(failureMessage));
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

    private void ensureSearchQuery(McpToolDefinition toolDefinition,
                                   Map<String, Object> arguments,
                                   SkillContext context) {
        if (!isSearchLikeTool(toolDefinition)) {
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
        candidate = LEADING_SEARCH_REQUEST_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = candidate.replaceFirst("^(?:看看|看下|看一下|查下|查一下|搜下|搜一下)\\s*", "").trim();
        candidate = LEADING_CONNECTOR_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = TRAILING_NEWS_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = LEADING_CONNECTOR_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = candidate.replaceAll("^[，。；、,.!?！？:：\\-]+", "").trim();
        candidate = candidate.replaceAll("[，。；、,.!?！？:：]+$", "").trim();
        if (candidate.length() <= 1 || ONLY_GENERIC_NEWS_PATTERN.matcher(candidate).matches()) {
            return "";
        }
        return candidate;
    }

    private boolean isSearchLikeTool(McpToolDefinition toolDefinition) {
        String text = normalizePhrase(toolDefinition.skillName() + " " + toolDefinition.description() + " " + toolDefinition.name());
        return containsAny(text, SEARCH_INTENT_CUES)
                || containsAny(text, REALTIME_INTENT_CUES)
                || text.contains("websearch")
                || text.contains("web search")
                || text.contains("searchdocs")
                || text.contains("search docs");
    }

    private boolean isMissingQueryError(RuntimeException ex) {
        String message = rootCauseMessage(ex).toLowerCase(Locale.ROOT);
        return message.contains("missing required parameter")
                && (message.contains("query") || message.contains("keyword") || message.contains("input"));
    }

    private String buildMissingQueryReply(SkillContext context) {
        String input = context == null ? "" : stringValue(context.input());
        if (input.isBlank()) {
            return "抱歉，我还不知道你想查什么。你可以直接说例如：科技新闻、AI 头条、成都天气、美元汇率。";
        }
        return "抱歉，我还没提取到明确的查询主题。请直接说你想查的内容，例如：科技新闻、AI 头条、成都天气、美元汇率。";
    }

    private String buildFriendlyFailureReply(String failureMessage) {
        String normalized = failureMessage == null ? "" : failureMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("403") || normalized.contains("forbidden") || normalized.contains("challenge")) {
            return "Brave search returned HTTP 403 (blocked by upstream challenge). 请检查 X-Subscription-Token、Brave 控制台配置与出口 IP 白名单后再重试。";
        }
        if (normalized.contains("timeout") || normalized.contains("connection reset") || normalized.contains("broken pipe")) {
            return "联网查询网络不稳定（" + failureMessage + "）。你可以稍后再试，我也可以先基于已有上下文给你一个离线总结。若你愿意，我之后会自动重试。";
        }
        return "当前外部查询工具调用失败：" + failureMessage;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? "unknown error"
                : current.getMessage();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
