package com.zhongbo.mindos.assistant.cli;

import com.zhongbo.mindos.assistant.common.nlu.MemoryIntentNlu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless natural-language parser that maps colloquial chat text to existing slash commands.
 */
class CommandNluParser {

    enum ConfidenceLevel {
        HIGH,
        LOW
    }

    record NaturalLanguageResolution(String command, ConfidenceLevel confidenceLevel) {
        static NaturalLanguageResolution none() {
            return new NaturalLanguageResolution(null, ConfidenceLevel.HIGH);
        }

        boolean isLowConfidence() {
            return command != null && confidenceLevel == ConfidenceLevel.LOW;
        }
    }

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
    private static final Pattern TODO_P1_PATTERN = Pattern.compile("(?:p1|优先级1|一级优先级|p1阈值)\\s*(?:改为|设为|设置为|=|:)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TODO_P2_PATTERN = Pattern.compile("(?:p2|优先级2|二级优先级|p2阈值)\\s*(?:改为|设为|设置为|=|:)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEACHING_TOPIC_BEFORE_PATTERN = Pattern.compile("([\\p{L}A-Za-z0-9+#._-]{2,32})\\s*(?:教学规划|学习计划|复习计划|课程规划)");
    private static final Pattern TEACHING_TOPIC_AFTER_PATTERN = Pattern.compile("(?:学|学习|复习|备考|课程)\\s*([\\p{L}A-Za-z0-9+#._-]{2,32})");
    private static final Pattern TEACHING_GOAL_PATTERN = Pattern.compile("(?:目标(?:是|为)?|想要|希望)\\s*([^，。；;\\n]+)");
    private static final Pattern TEACHING_DURATION_PATTERN = Pattern.compile("([0-9零一二两三四五六七八九十百千万]+)\\s*(?:周|weeks?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEACHING_WEEKLY_HOURS_PATTERN = Pattern.compile("(?:每周|一周)\\s*([0-9零一二两三四五六七八九十百千万]+)\\s*(?:小时|h|hours?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEACHING_LEVEL_PATTERN = Pattern.compile("(?:年级|阶段|level|级别)\\s*[:：]?\\s*([A-Za-z0-9一二三四五六七八九十高初大研Gg-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEACHING_STUDENT_ID_PATTERN = Pattern.compile("(?:学生|student)\\s*(?:id|ID)?\\s*[:：]?\\s*([A-Za-z0-9._-]+)");
    private static final Pattern TEACHING_WEAK_TOPICS_PATTERN = Pattern.compile("(?:薄弱点|薄弱科目|弱项)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern TEACHING_STRONG_TOPICS_PATTERN = Pattern.compile("(?:优势项|擅长|强项)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern TEACHING_STYLE_PATTERN = Pattern.compile("(?:学习风格|学习方式)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern TEACHING_CONSTRAINTS_PATTERN = Pattern.compile("(?:约束|限制|不可用时段)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern EQ_STYLE_PATTERN = Pattern.compile("(?:风格|语气|版本|style)\\s*(?:改为|设为|设置为|用|使用|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EQ_MODE_PATTERN = Pattern.compile("(?:模式|mode)\\s*(?:改为|设为|设置为|用|使用|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EQ_PRIORITY_FOCUS_PATTERN = Pattern.compile("(?:优先级聚焦|priority\\s*focus|focus)\\s*(?:改为|设为|设置为|用|使用|=|:)\\s*([^,，。；;\\n]+)", Pattern.CASE_INSENSITIVE);

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

        String memoryStyleCommand = extractMemoryStyleCommand(input, normalized);
        if (memoryStyleCommand != null) {
            return memoryStyleCommand;
        }

        String memoryCompressCommand = extractMemoryCompressCommand(input, normalized);
        if (memoryCompressCommand != null) {
            return memoryCompressCommand;
        }

        String memoryPullCommand = extractMemoryPullCommand(normalized);
        if (memoryPullCommand != null) {
            return memoryPullCommand;
        }

        String memoryPushCommand = extractMemoryPushCommand(normalized);
        if (memoryPushCommand != null) {
            return memoryPushCommand;
        }

        String todoPolicyCommand = extractTodoPolicyCommand(input, normalized);
        if (todoPolicyCommand != null) {
            return todoPolicyCommand;
        }

        String userCommand = extractUserCommand(input, normalized);
        if (userCommand != null) {
            return userCommand;
        }

        String skillLoadCommand = extractSkillLoadCommand(input, normalized);
        if (skillLoadCommand != null) {
            return skillLoadCommand;
        }

        String teachingPlanCommand = extractTeachingPlanCommand(input, normalized);
        if (teachingPlanCommand != null) {
            return teachingPlanCommand;
        }

        String eqCoachCommand = extractEqCoachCommand(input, normalized);
        if (eqCoachCommand != null) {
            return eqCoachCommand;
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
        if (containsAny(normalized,
                "重试",
                "再试一次",
                "retry",
                "继续上次",
                "继续刚才",
                "按之前方式",
                "按上次方式")) {
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
        if (containsAny(normalized,
                "显示路由细节",
                "打开路由细节",
                "开启路由细节",
                "打开排障模式",
                "开启排障模式",
                "show routing details",
                "enable debug mode")) {
            return "/routing on";
        }
        if (containsAny(normalized,
                "隐藏路由细节",
                "关闭路由细节",
                "关闭排障模式",
                "退出排障模式",
                "hide routing details",
                "disable debug mode")) {
            return "/routing off";
        }
        if (containsAny(normalized,
                "当前路由模式",
                "查看路由模式",
                "排障模式状态",
                "routing mode",
                "debug mode")) {
            return "/routing";
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

    NaturalLanguageResolution resolveNaturalLanguage(String input) {
        String command = resolveNaturalLanguageCommand(input);
        if (command == null) {
            return NaturalLanguageResolution.none();
        }
        return new NaturalLanguageResolution(command, isLowConfidenceIntent(input, command)
                ? ConfidenceLevel.LOW
                : ConfidenceLevel.HIGH);
    }

    private boolean isLowConfidenceIntent(String input, String command) {
        if (command == null || input == null) {
            return false;
        }
        if (!command.startsWith("/teach plan")) {
            return false;
        }
        Map<String, Object> payload = parseTeachingPlanInput(input);
        int signalCount = 0;
        if (payload.containsKey("topic")) {
            signalCount++;
        }
        if (payload.containsKey("goal")) {
            signalCount++;
        }
        if (payload.containsKey("durationWeeks")) {
            signalCount++;
        }
        if (payload.containsKey("weeklyHours")) {
            signalCount++;
        }
        if (payload.containsKey("studentId")) {
            signalCount++;
        }

        String normalized = normalizeNaturalLanguage(input);
        boolean hasActionVerb = containsAny(normalized,
                "做一个", "做一份", "制定", "生成", "安排", "study plan", "teaching plan");
        return signalCount < 2 && !hasActionVerb;
    }

    Map<String, Object> parseTeachingPlanInput(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "studentId", extractByPattern(input, TEACHING_STUDENT_ID_PATTERN));
        putIfPresent(payload, "topic", extractTeachingTopic(input));
        putIfPresent(payload, "goal", extractByPattern(input, TEACHING_GOAL_PATTERN));
        putIfPresent(payload, "gradeOrLevel", extractByPattern(input, TEACHING_LEVEL_PATTERN));

        Integer durationWeeks = extractFlexibleNumber(input, TEACHING_DURATION_PATTERN);
        if (durationWeeks != null && durationWeeks > 0) {
            payload.put("durationWeeks", durationWeeks);
        }
        Integer weeklyHours = extractFlexibleNumber(input, TEACHING_WEEKLY_HOURS_PATTERN);
        if (weeklyHours != null && weeklyHours > 0) {
            payload.put("weeklyHours", weeklyHours);
        }

        putListIfPresent(payload, "weakTopics", extractDelimitedValues(input, TEACHING_WEAK_TOPICS_PATTERN));
        putListIfPresent(payload, "strongTopics", extractDelimitedValues(input, TEACHING_STRONG_TOPICS_PATTERN));
        putListIfPresent(payload, "learningStyle", extractDelimitedValues(input, TEACHING_STYLE_PATTERN));
        putListIfPresent(payload, "constraints", extractDelimitedValues(input, TEACHING_CONSTRAINTS_PATTERN));
        return Map.copyOf(payload);
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

    private String extractMemoryStyleCommand(String input, String normalized) {
        if (MemoryIntentNlu.isStyleShowIntent(input)) {
            return "/memory style show";
        }
        String autoTuneSample = MemoryIntentNlu.extractAutoTuneSample(input);
        if (containsAny(normalized, "自动微调记忆风格", "根据这段话微调记忆风格", "自动调整记忆风格")) {
            if (autoTuneSample == null || autoTuneSample.isBlank()) {
                return "/memory style set --auto-tune";
            }
            return "/memory style set --auto-tune --sample-text " + autoTuneSample;
        }
        MemoryIntentNlu.StyleUpdateIntent styleUpdateIntent = MemoryIntentNlu.extractStyleUpdateIntent(input);
        if (styleUpdateIntent == null) {
            return null;
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (styleUpdateIntent.styleName() != null) {
            options.put("style-name", styleUpdateIntent.styleName());
        }
        if (styleUpdateIntent.tone() != null) {
            options.put("tone", styleUpdateIntent.tone());
        }
        if (styleUpdateIntent.outputFormat() != null) {
            options.put("output-format", styleUpdateIntent.outputFormat());
        }
        if (options.isEmpty()) {
            return "/memory style show";
        }
        StringBuilder command = new StringBuilder("/memory style set");
        for (Map.Entry<String, String> entry : options.entrySet()) {
            command.append(" --").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return command.toString();
    }

    private String extractMemoryCompressCommand(String input, String normalized) {
        MemoryIntentNlu.CompressionIntent intent = MemoryIntentNlu.extractCompressionIntent(input);
        if (intent == null) {
            return null;
        }
        String source = intent.source();
        String focus = intent.focus();
        if (source == null || source.isBlank()) {
            return focus == null ? "/memory compress" : "/memory compress --focus " + focus;
        }
        if (focus == null) {
            return "/memory compress --source " + source;
        }
        return "/memory compress --source " + source + " --focus " + focus;
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

    private String extractTodoPolicyCommand(String input, String normalized) {
        if (containsAny(normalized, "恢复待办策略默认", "重置待办策略", "todo policy reset")) {
            return "/todo policy reset";
        }
        if (containsAny(normalized, "查看待办策略", "当前待办策略", "todo policy")) {
            return "/todo policy show";
        }
        if (!containsAny(normalized, "待办策略", "todo policy", "p1", "p2")) {
            return null;
        }
        Matcher p1Matcher = TODO_P1_PATTERN.matcher(input == null ? "" : input);
        Matcher p2Matcher = TODO_P2_PATTERN.matcher(input == null ? "" : input);
        String p1 = p1Matcher.find() ? p1Matcher.group(1) : null;
        String p2 = p2Matcher.find() ? p2Matcher.group(1) : null;
        if (p1 == null && p2 == null) {
            return null;
        }
        StringBuilder command = new StringBuilder("/todo policy set");
        if (p1 != null) {
            command.append(" --p1-threshold ").append(p1);
        }
        if (p2 != null) {
            command.append(" --p2-threshold ").append(p2);
        }
        return command.toString();
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

    private String extractTeachingPlanCommand(String input, String normalized) {
        if (!containsAny(normalized,
                "教学规划",
                "学习计划",
                "复习计划",
                "课程规划",
                "study plan",
                "teaching plan")) {
            return null;
        }
        String query = input.replaceAll("[\\r\\n\\t]+", " ")
                .replace("--", " ")
                .trim();
        if (query.isBlank()) {
            return "/teach plan";
        }
        return "/teach plan --query " + query;
    }

    private String extractEqCoachCommand(String input, String normalized) {
        if (!containsAny(normalized,
                "高情商",
                "情商",
                "沟通建议",
                "沟通攻略",
                "心理分析",
                "事情分析",
                "分析这件事",
                "怎么说",
                "eq coach",
                "eq.coach")) {
            return null;
        }
        String query = input == null ? "" : input.replaceAll("[\\r\\n\\t]+", " ").replace("--", " ").trim();
        if (query.isBlank()) {
            return "/eq coach";
        }

        String style = normalizeEqStyle(extractByPattern(input, EQ_STYLE_PATTERN), normalized);
        if (style == null) {
            style = normalizeEqStyle(null, normalized);
        }
        String mode = normalizeEqMode(extractByPattern(input, EQ_MODE_PATTERN), normalized);
        String priorityFocus = normalizeEqPriorityFocus(extractByPattern(input, EQ_PRIORITY_FOCUS_PATTERN), normalized);
        query = sanitizeEqQuery(query);
        StringBuilder command = new StringBuilder("/eq coach --query ").append(query);
        if (style != null) {
            command.append(" --style ").append(style);
        }
        if (mode != null) {
            command.append(" --mode ").append(mode);
        }
        if (priorityFocus != null) {
            command.append(" --priority-focus ").append(priorityFocus);
        }
        return command.toString();
    }

    private String normalizeEqStyle(String explicitStyle, String normalizedInput) {
        String raw = explicitStyle == null ? normalizedInput : explicitStyle.toLowerCase();
        if (raw == null) {
            return null;
        }
        if (raw.contains("温和") || raw.contains("gentle")) {
            return "gentle";
        }
        if (raw.contains("直接") || raw.contains("direct")) {
            return "direct";
        }
        if (raw.contains("职场") || raw.contains("work")) {
            return "workplace";
        }
        if (raw.contains("亲密") || raw.contains("伴侣") || raw.contains("关系") || raw.contains("intimate")) {
            return "intimate";
        }
        return null;
    }

    private String normalizeEqMode(String explicitMode, String normalizedInput) {
        String raw = explicitMode == null ? normalizedInput : explicitMode.toLowerCase();
        if (raw == null) {
            return null;
        }
        if (raw.contains("都要")
                || raw.contains("都给")
                || raw.contains("完整")
                || raw.contains("both")
                || (raw.contains("分析") && raw.contains("回复"))) {
            return "both";
        }
        if (raw.contains("analysis") || raw.contains("只分析") || raw.contains("分析")) {
            return "analysis";
        }
        if (raw.contains("reply") || raw.contains("回复") || raw.contains("话术")) {
            return "reply";
        }
        return null;
    }

    private String normalizeEqPriorityFocus(String explicitFocus, String normalizedInput) {
        String raw = explicitFocus == null ? normalizedInput : explicitFocus.toLowerCase();
        if (raw == null) {
            return null;
        }
        if (raw.contains("p1")
                || raw.contains("优先级1")
                || raw.contains("一级优先")
                || raw.contains("先看p1")
                || raw.contains("最高优先")
                || raw.contains("最紧急")
                || raw.contains("highest priority")
                || raw.contains("critical")) {
            return "p1";
        }
        if (raw.contains("p2")
                || raw.contains("优先级2")
                || raw.contains("二级优先")
                || raw.contains("先看p2")
                || raw.contains("次优先")
                || raw.contains("中优先")) {
            return "p2";
        }
        if (raw.contains("p3")
                || raw.contains("优先级3")
                || raw.contains("三级优先")
                || raw.contains("先看p3")
                || raw.contains("低优先")
                || raw.contains("后续优先")) {
            return "p3";
        }
        return null;
    }

    private String sanitizeEqQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String cleaned = query;
        cleaned = cleaned.replaceAll("(?:，|,)?\\s*(?:风格|语气|版本|style)\\s*(?:改为|设为|设置为|用|使用|=|:)?\\s*(?:温和版|直接版|职场版|亲密关系版|gentle|direct|workplace|intimate)\\s*$", "");
        cleaned = cleaned.replaceAll("(?:，|,)?\\s*(?:用|使用)?\\s*(?:温和版|直接版|职场版|亲密关系版|gentle|direct|workplace|intimate)\\s*$", "");
        cleaned = cleaned.replaceAll("(?:，|,)?\\s*(?:模式|mode)\\s*(?:改为|设为|设置为|用|使用|=|:)?\\s*(?:analysis|reply|both|分析|回复|都要|都给|完整)\\s*$", "");
        cleaned = cleaned.replaceAll("(?:，|,)?\\s*(?:只做|只要|仅)?\\s*(?:分析|回复|话术|analysis|reply|both|都要|都给|完整)\\s*$", "");
        cleaned = cleaned.replaceAll("(?:，|,)?\\s*(?:优先级聚焦|priority\\s*focus|focus)\\s*(?:改为|设为|设置为|用|使用|=|:)?\\s*(?:p1|p2|p3|优先级1|优先级2|优先级3|一级优先|二级优先|三级优先)\\s*$", "");
        cleaned = cleaned.replaceAll("(?:，|,)?\\s*(?:先看|优先看|先做|先处理)?\\s*(?:p1|p2|p3|优先级1|优先级2|优先级3|一级优先|二级优先|三级优先|最高优先级|最紧急|次优先|中优先|低优先|后续优先)\\s*$", "");
        return cleaned.trim();
    }

    private String extractTeachingTopic(String input) {
        Matcher beforeMatcher = TEACHING_TOPIC_BEFORE_PATTERN.matcher(input);
        if (beforeMatcher.find()) {
            return sanitizeTopic(beforeMatcher.group(1));
        }
        Matcher afterMatcher = TEACHING_TOPIC_AFTER_PATTERN.matcher(input);
        if (afterMatcher.find()) {
            return sanitizeTopic(afterMatcher.group(1));
        }
        return null;
    }

    private Integer extractFlexibleNumber(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        return parseFlexibleNumber(matcher.group(1));
    }

    private String extractByPattern(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private List<String> extractDelimitedValues(String input, Pattern pattern) {
        String raw = extractByPattern(input, pattern);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (String part : raw.split("[,，;；/、]")) {
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private void putListIfPresent(Map<String, Object> payload, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            payload.put(key, values);
        }
    }

    private String sanitizeTopic(String rawTopic) {
        if (rawTopic == null || rawTopic.isBlank()) {
            return null;
        }
        return rawTopic.trim()
                .replaceFirst("^(给我一个|给我一份|给我|帮我做|帮我|请帮我|请|做个|做一份|做一个)", "")
                .trim();
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

