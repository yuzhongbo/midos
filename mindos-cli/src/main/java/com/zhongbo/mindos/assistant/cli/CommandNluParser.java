package com.zhongbo.mindos.assistant.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless natural-language parser that maps colloquial chat text to existing slash commands.
 */
class CommandNluParser {

    private static final int MAX_PROFILE_FIELD_LENGTH = 120;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("(?:localhost|[a-zA-Z0-9.-]+):(\\d{2,5})(?:/[^\\s]*)?");
    private static final Pattern MCP_ALIAS_PATTERN = Pattern.compile("(?:alias|别名|简称|代号|命名为|命名成|叫做|叫)\\s*[:：]?\\s*([a-zA-Z0-9._-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern SINCE_PATTERN = Pattern.compile("(?:since|从|游标|cursor)\\s*[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?:limit|限制|最多|最近)\\s*[:：]?\\s*([0-9零一二两三四五六七八九十百千万]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_PATTERN = Pattern.compile("(?:拉取|拉|获取|同步|查看|显示|保存|记录|写入|最近)\\s*([0-9零一二两三四五六七八九十百千万]+)\\s*条", Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_PATTERN = Pattern.compile("(?:用户|user)\\s*(?:改为|设为|设置为|切换到|切到|为|=)?\\s*([a-zA-Z0-9._-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("(?:名字|名称|assistant\\s*name|name)\\s*(?:改为|改成|换成|设为|设成|设置为|设置成|命名为|叫|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_ROLE_PATTERN = Pattern.compile("(?:角色|role)\\s*(?:改为|改成|换成|设为|设成|设置为|设置成|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_STYLE_PATTERN = Pattern.compile("(?:风格|语气|style)\\s*(?:改为|改成|换成|设为|设成|设置为|设置成|想用|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_LANGUAGE_PATTERN = Pattern.compile("(?:语言|language)\\s*(?:改为|改成|换成|设为|设成|设置为|设置成|想用|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_TIMEZONE_PATTERN = Pattern.compile("(?:时区|timezone)\\s*(?:改为|改成|换成|设为|设成|设置为|设置成|是|为|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);

    String resolveNaturalLanguageCommand(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = normalizeNaturalLanguage(input);

        String profileSetCommand = extractProfileSetCommand(input, normalized);
        if (profileSetCommand != null) {
            return profileSetCommand;
        }

        String historyCommand = extractHistoryCommand(normalized);
        if (historyCommand != null) {
            return historyCommand;
        }

        String memoryPullCommand = extractMemoryPullCommand(normalized);
        if (memoryPullCommand != null) {
            return memoryPullCommand;
        }

        String memoryPushCommand = extractMemoryPushCommand(normalized);
        if (memoryPushCommand != null) {
            return memoryPushCommand;
        }

        String userCommand = extractUserCommand(input, normalized);
        if (userCommand != null) {
            return userCommand;
        }

        String skillLoadCommand = extractSkillLoadCommand(input, normalized);
        if (skillLoadCommand != null) {
            return skillLoadCommand;
        }

        if (containsAny(normalized, "帮助", "命令", "怎么用", "help")) {
            return "/help";
        }
        if (containsAny(normalized, "会话信息", "当前会话", "session")) {
            return "/session";
        }
        if (containsAny(normalized, "退出", "结束", "quit", "bye")) {
            return "/exit";
        }
        if (containsAny(normalized, "清空窗口", "清空会话", "clear")) {
            return "/clear";
        }
        if (containsAny(normalized, "重试", "再试一次", "retry")) {
            return "/retry";
        }
        if (containsAny(normalized, "有哪些技能", "技能列表", "你会什么", "你能做什么", "skills")) {
            return "/skills";
        }
        if (containsAny(normalized, "查看配置", "查看profile", "profile show")) {
            return "/profile show";
        }
        if (containsAny(normalized, "重置配置", "重置profile", "恢复默认配置", "profile reset")) {
            return "/profile reset";
        }
        if (containsAny(normalized, "重载mcp", "reload mcp")) {
            return "/skill reload-mcp";
        }
        if (containsAny(normalized, "重载技能", "刷新技能", "reload skills")) {
            return "/skill reload";
        }

        String serverUrl = extractServerUrl(input);
        if (serverUrl != null && containsAny(normalized,
                "切换服务",
                "切换server",
                "server ",
                "服务地址",
                "服务器地址",
                "服务端地址")) {
            return "/server " + serverUrl;
        }

        String provider = extractProvider(normalized);
        if (provider != null) {
            return "/provider " + provider;
        }

        return null;
    }

    private String extractHistoryCommand(String normalized) {
        if (!containsAny(normalized, "历史", "聊天记录", "对话记录", "history")) {
            return null;
        }
        Integer limit = extractLimit(normalized);
        if (limit == null) {
            return "/history";
        }
        return "/history --limit " + limit;
    }

    private String extractMemoryPullCommand(String normalized) {
        boolean explicitMemoryPull = containsAny(normalized, "拉取记忆", "同步记忆", "memory pull", "memorypull");
        boolean separatedWordsMemoryPull = (normalized.contains("拉取") || normalized.contains("拉"))
                && normalized.contains("记忆");
        Integer countLimit = extractCountLimit(normalized);
        boolean colloquialPullWithoutMemoryKeyword = countLimit != null
                && (normalized.contains("拉") || normalized.contains("同步") || normalized.contains("获取"))
                && !containsAny(normalized, "历史", "聊天记录", "对话记录");
        if (!explicitMemoryPull && !separatedWordsMemoryPull && !colloquialPullWithoutMemoryKeyword) {
            return null;
        }
        Long since = extractSince(normalized);
        Integer limit = countLimit;
        if (limit == null) {
            limit = extractLimit(normalized, false);
        }
        StringBuilder command = new StringBuilder("/memory pull");
        if (since != null) {
            command.append(" --since ").append(since);
        }
        if (limit != null) {
            command.append(" --limit ").append(limit);
        }
        return command.toString();
    }

    private String extractMemoryPushCommand(String normalized) {
        boolean explicitMemoryPush = containsAny(normalized, "保存记忆", "记录记忆", "写入记忆", "memory push", "memorypush");
        boolean separatedWordsMemoryPush = (normalized.contains("保存") || normalized.contains("记录") || normalized.contains("写入"))
                && normalized.contains("记忆");
        if (!explicitMemoryPush && !separatedWordsMemoryPush) {
            return null;
        }
        Integer limit = extractCountLimit(normalized);
        if (limit == null) {
            limit = extractLimit(normalized, false);
        }
        if (limit == null) {
            return "/memory push";
        }
        return "/memory push --limit " + limit;
    }

    private String extractUserCommand(String input, String normalized) {
        if (!containsAny(normalized, "切换用户", "用户改为", "user")) {
            return null;
        }
        Matcher matcher = USER_PATTERN.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        return "/user " + matcher.group(1).trim();
    }

    private String extractSkillLoadCommand(String input, String normalized) {
        String serverUrl = extractServerUrl(input);
        if (serverUrl == null) {
            return null;
        }
        if (containsAny(normalized, "加载jar", "load jar", "skill jar")) {
            return "/skill load-jar --url " + serverUrl;
        }
        if (containsAny(normalized, "加载mcp", "接入mcp", "load mcp")) {
            String alias = extractMcpAlias(input);
            if (alias == null || alias.isBlank()) {
                alias = deriveAliasFromUrl(serverUrl);
            }
            return "/skill load-mcp --alias " + alias + " --url " + serverUrl;
        }
        return null;
    }

    private String extractProfileSetCommand(String input, String normalized) {
        boolean looksLikeProfileUpdate = containsAny(normalized,
                "profile",
                "配置",
                "助手",
                "名字",
                "名称",
                "角色",
                "风格",
                "语言",
                "时区");
        if (!looksLikeProfileUpdate) {
            return null;
        }

        Map<String, String> options = new LinkedHashMap<>();
        putMatchedProfileOption(options, "name", PROFILE_NAME_PATTERN, input);
        putMatchedProfileOption(options, "role", PROFILE_ROLE_PATTERN, input);
        putMatchedProfileOption(options, "style", PROFILE_STYLE_PATTERN, input);
        putMatchedProfileOption(options, "language", PROFILE_LANGUAGE_PATTERN, input);
        putMatchedProfileOption(options, "timezone", PROFILE_TIMEZONE_PATTERN, input);
        String provider = extractProvider(normalized);
        if (provider != null) {
            options.put("llm-provider", provider);
        }
        if (options.isEmpty()) {
            return null;
        }

        StringBuilder command = new StringBuilder("/profile set");
        for (Map.Entry<String, String> entry : options.entrySet()) {
            command.append(" --").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return command.toString();
    }

    private void putMatchedProfileOption(Map<String, String> options, String key, Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            String value = sanitizeProfileValue(matcher.group(1));
            if (value != null && !value.isBlank()) {
                options.put(key, value);
            }
        }
    }

    private String sanitizeProfileValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        while (!normalized.isEmpty() && "，,。；;".indexOf(normalized.charAt(normalized.length() - 1)) >= 0) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > MAX_PROFILE_FIELD_LENGTH) {
            return null;
        }
        // Block option-like payloads to avoid accidental command injection when building slash commands.
        if (normalized.contains("--")
                || normalized.contains("/profile")
                || normalized.contains("/skill")
                || normalized.contains("/server")
                || normalized.contains("/memory")) {
            return null;
        }
        return normalized;
    }

    private Long extractSince(String normalized) {
        Matcher matcher = SINCE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return parseLong(matcher.group(1), 0L);
        }
        return null;
    }

    private Integer extractLimit(String normalized) {
        return extractLimit(normalized, true);
    }

    private Integer extractLimit(String normalized, boolean allowBareNumberFallback) {
        Matcher limitMatcher = LIMIT_PATTERN.matcher(normalized);
        if (limitMatcher.find()) {
            Integer parsed = parseFlexibleNumber(limitMatcher.group(1));
            if (parsed != null && parsed > 0) {
                return parsed;
            }
        }
        if (normalized.contains("几条") || normalized.contains("若干条")) {
            return 10;
        }
        if (!allowBareNumberFallback) {
            return null;
        }
        Matcher numberMatcher = NUMBER_PATTERN.matcher(normalized);
        if (numberMatcher.find()) {
            int parsed = parseInt(numberMatcher.group(1), 0);
            return parsed > 0 ? parsed : null;
        }
        return null;
    }

    private Integer extractCountLimit(String normalized) {
        Matcher countMatcher = COUNT_PATTERN.matcher(normalized);
        if (!countMatcher.find()) {
            return null;
        }
        Integer parsed = parseFlexibleNumber(countMatcher.group(1));
        if (parsed == null || parsed <= 0) {
            return null;
        }
        return parsed;
    }

    private Integer parseFlexibleNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        return parseSimpleChineseNumber(normalized);
    }

    private Integer parseSimpleChineseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('两', '二');
        while (normalized.startsWith("零")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return 0;
        }
        Integer withWan = parseChineseUnitNumber(normalized, '万', 10_000);
        if (withWan != null) {
            return withWan;
        }
        Integer withThousands = parseChineseUnitNumber(normalized, '千', 1000);
        if (withThousands != null) {
            return withThousands;
        }
        Integer withHundreds = parseChineseUnitNumber(normalized, '百', 100);
        if (withHundreds != null) {
            return withHundreds;
        }
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            Integer tens = chineseDigit(normalized.charAt(0));
            return tens == null ? null : tens * 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            Integer ones = chineseDigit(normalized.charAt(1));
            return ones == null ? null : 10 + ones;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            Integer tens = chineseDigit(normalized.charAt(0));
            Integer ones = chineseDigit(normalized.charAt(2));
            return (tens == null || ones == null) ? null : tens * 10 + ones;
        }
        if (normalized.length() == 1) {
            return chineseDigit(normalized.charAt(0));
        }
        return null;
    }

    private Integer parseChineseUnitNumber(String normalized, char unitChar, int unitValue) {
        int unitIndex = normalized.indexOf(unitChar);
        if (unitIndex < 0) {
            return null;
        }
        String headPart = normalized.substring(0, unitIndex);
        String tailPart = normalized.substring(unitIndex + 1);

        Integer head = headPart.isBlank() ? 1 : parseSimpleChineseNumber(headPart);
        if (head == null) {
            return null;
        }

        if (tailPart.isBlank()) {
            return head * unitValue;
        }

        Integer tail = parseSimpleChineseNumber(tailPart);
        return tail == null ? null : head * unitValue + tail;
    }

    private Integer chineseDigit(char c) {
        return switch (c) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> null;
        };
    }

    private String deriveAliasFromUrl(String serverUrl) {
        String value = serverUrl
                .replaceFirst("https?://", "")
                .replaceAll("[:/].*$", "")
                .trim();
        if (value.isBlank()) {
            return "mcp";
        }
        String host = value.split("\\.")[0];
        String alias = host.replaceAll("[^a-zA-Z0-9._-]+", "-");
        return alias.isBlank() ? "mcp" : alias;
    }

    private String normalizeNaturalLanguage(String value) {
        return value.toLowerCase()
                .replace('？', '?')
                .replace('。', ' ')
                .replace('，', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractUrl(String input) {
        Matcher matcher = URL_PATTERN.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        String candidate = stripTrailingPunctuation(matcher.group());
        return normalizeHttpUrl(candidate);
    }

    private String extractServerUrl(String input) {
        String explicitUrl = extractUrl(input);
        if (explicitUrl != null) {
            return explicitUrl;
        }
        // If user explicitly provided an HTTP(S) URL-like token but validation failed, do not fallback.
        if (input.contains("http://") || input.contains("https://")) {
            return null;
        }
        Matcher hostPortMatcher = HOST_PORT_PATTERN.matcher(input);
        if (!hostPortMatcher.find()) {
            return null;
        }
        String candidate = stripTrailingPunctuation(hostPortMatcher.group());
        String withScheme = candidate.startsWith("http") ? candidate : "http://" + candidate;
        return normalizeHttpUrl(withScheme);
    }

    private String stripTrailingPunctuation(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (!normalized.isEmpty() && "，,。；;！？!?".indexOf(normalized.charAt(normalized.length() - 1)) >= 0) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeHttpUrl(String rawUrl) {
        return UrlSecurityPolicy.normalizeSecureHttpUrl(rawUrl);
    }

    private String extractMcpAlias(String input) {
        Matcher matcher = MCP_ALIAS_PATTERN.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim();
    }

    private String extractProvider(String normalized) {
        if (!containsAny(normalized, "provider", "供应商", "模型", "llm")) {
            return null;
        }
        if (containsAny(normalized,
                "default",
                "默认",
                "恢复默认",
                "清除覆盖",
                "取消覆盖",
                "取消模型覆盖",
                "还原默认")) {
            return "default";
        }
        if (containsAny(normalized, "openai")) {
            return "openai";
        }
        if (containsAny(normalized, "anthropic", "claude")) {
            return "anthropic";
        }
        if (containsAny(normalized, "qwen", "通义")) {
            return "qwen";
        }
        if (containsAny(normalized, "deepseek")) {
            return "deepseek";
        }
        if (containsAny(normalized, "ollama", "local", "本地")) {
            return "local";
        }
        return null;
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}

