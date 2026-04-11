package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FileSearchSkill implements Skill, SkillDescriptorProvider {
    private static final Logger LOGGER = Logger.getLogger(FileSearchSkill.class.getName());
    private static final Pattern PATH_ATTR_PATTERN = Pattern.compile("(?i)(?:路径|目录|path)\\s*[:：=]\\s*([`\"']?[^\\s,，。；;]+[`\"']?)");
    private static final Pattern KEYWORD_ATTR_PATTERN = Pattern.compile("(?i)(?:关键词|关键字|keyword|包含)\\s*[:：=]\\s*([`\"']?[^,，。；;]+[`\"']?)");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)(?:前|最多|limit\\s*=?)\\s*([0-9一二两三四五六七八九十]+)\\s*(?:个|条|项|份|个文件)?");
    private static final Map<String, String> FILE_TYPE_ALIASES = Map.ofEntries(
            Map.entry("java文件", ".java"),
            Map.entry("java", ".java"),
            Map.entry(".java", ".java"),
            Map.entry("markdown", ".md"),
            Map.entry("md", ".md"),
            Map.entry(".md", ".md"),
            Map.entry("yaml", ".yaml"),
            Map.entry("yml", ".yml"),
            Map.entry(".yaml", ".yaml"),
            Map.entry(".yml", ".yml"),
            Map.entry("json", ".json"),
            Map.entry(".json", ".json"),
            Map.entry("xml", ".xml"),
            Map.entry(".xml", ".xml"),
            Map.entry("properties", ".properties"),
            Map.entry(".properties", ".properties")
    );
    private final LlmClient llmClient;

    public FileSearchSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public FileSearchSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "file.search";
    }

    @Override
    public String description() {
        return "按路径和关键词整理候选文件，适合快速缩小排查范围。";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("找文件", "查文件", "搜索文件", "search file", "grep", "目录", "路径"));
    }

    @Override
    public SkillResult run(SkillContext context) {
        SearchRequest request = resolveRequest(context);
        if (llmClient != null) {
            try {
                String prompt = "你是一个文件搜索助手，请根据如下条件返回简洁的候选文件名与检索建议，仅输出文本。路径："
                        + request.path()
                        + "，关键词："
                        + request.keyword()
                        + "，文件类型："
                        + request.fileType()
                        + "，建议候选数："
                        + request.limit();
                String llmReply = llmClient.generateResponse(prompt, buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for file.search skill, fallback to local output", ex);
            }
        }
        return SkillResult.success(name(), buildDeterministicOutput(request));
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private SearchRequest resolveRequest(SkillContext context) {
        String input = context == null || context.input() == null ? "" : context.input().trim();
        String path = normalizePath(firstNonBlank(
                asString(context, "path", ""),
                extract(input, PATH_ATTR_PATTERN),
                inferPathFromInput(input),
                "./"
        ));
        String fileType = normalizeFileType(firstNonBlank(
                asString(context, "fileType", ""),
                asString(context, "type", ""),
                inferFileTypeFromInput(input)
        ));
        String keyword = normalizeKeyword(firstNonBlank(
                asString(context, "keyword", ""),
                extract(input, KEYWORD_ATTR_PATTERN),
                inferKeywordFromInput(input, path, fileType)
        ));
        int limit = parseLimit(firstNonBlank(asString(context, "limit", ""), extract(input, LIMIT_PATTERN)));
        return new SearchRequest(path, keyword, fileType, limit);
    }

    private String buildDeterministicOutput(SearchRequest request) {
        StringBuilder output = new StringBuilder();
        output.append("我先帮你缩小范围：\n");
        output.append("- 路径：").append(request.path()).append("\n");
        output.append("- 关键词：").append(request.keyword().isBlank() ? "未指定" : request.keyword()).append("\n");
        output.append("- 文件类型：").append(request.fileType().isBlank() ? "未指定" : request.fileType()).append("\n");
        output.append("- 建议候选数：").append(request.limit()).append("\n");
        output.append("建议检索顺序：\n");
        output.append("1. 先在 `").append(request.path()).append("` 下按文件名筛选");
        if (!request.fileType().isBlank()) {
            output.append(" `*").append(request.fileType()).append("`");
        }
        output.append("。\n");
        if (!request.keyword().isBlank()) {
            output.append("2. 优先匹配文件名或目录名中包含 “").append(request.keyword()).append("” 的候选。\n");
            output.append("3. 再在命中文件内搜索关键词片段：").append(String.join(" / ", buildKeywordTokens(request.keyword()))).append("。\n");
        } else {
            output.append("2. 先按模块目录、文件名语义和最近改动范围缩小候选。\n");
            output.append("3. 如果再补一个关键词，我可以继续把候选压到更小范围。\n");
        }
        List<String> patterns = buildCandidatePatterns(request.keyword(), request.fileType(), request.limit());
        if (!patterns.isEmpty()) {
            output.append("建议优先关注的文件名模式：").append(String.join("、", patterns)).append("。");
        }
        return output.toString().trim();
    }

    private List<String> buildCandidatePatterns(String keyword, String fileType, int limit) {
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        String extension = fileType == null ? "" : fileType;
        for (String token : buildKeywordTokens(keyword)) {
            patterns.add("*" + token + "*" + extension);
            patterns.add(capitalize(token) + "*" + extension);
            if (patterns.size() >= Math.max(2, limit)) {
                break;
            }
        }
        if (patterns.isEmpty() && !extension.isBlank()) {
            patterns.add("*" + extension);
        }
        return List.copyOf(patterns);
    }

    private List<String> buildKeywordTokens(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String normalized = keyword.trim()
                .replaceAll("(?<=[\\p{IsHan}])(?=[\\p{L}\\p{N}])|(?<=[\\p{L}\\p{N}])(?=[\\p{IsHan}])", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : normalized.split("[\\s/,_-]+")) {
            String candidate = part.trim();
            if (candidate.length() >= 2) {
                tokens.add(candidate);
            }
        }
        return List.copyOf(tokens);
    }

    private String inferPathFromInput(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        for (String token : tokenizeInput(input)) {
            String candidate = stripQuotes(token);
            if (candidate.startsWith("./") || candidate.startsWith("/") || candidate.contains("/")) {
                return candidate;
            }
        }
        return "";
    }

    private String inferFileTypeFromInput(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : FILE_TYPE_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    private String inferKeywordFromInput(String input, String path, String fileType) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String candidate = input;
        candidate = candidate.replaceFirst("(?i)^file\\.search", "").trim();
        candidate = candidate.replaceAll("(?i)(找文件|查文件|搜索文件|search file|grep|帮我|请帮我|看看|找一下)", " ");
        candidate = candidate.replaceAll("(?i)(路径|目录|path|关键词|关键字|keyword|文件|文件名|包含|搜索|查找)\\s*[:：=]?\\s*", " ");
        if (path != null && !path.isBlank()) {
            candidate = candidate.replace(path, " ");
        }
        if (fileType != null && !fileType.isBlank()) {
            candidate = candidate.replace(fileType, " ");
        }
        for (String alias : FILE_TYPE_ALIASES.keySet()) {
            candidate = candidate.replace(alias, " ");
        }
        candidate = LIMIT_PATTERN.matcher(candidate).replaceAll(" ");
        candidate = candidate.replaceAll("[`\"']", " ");
        candidate = candidate.replaceAll("[，,。；;]+", " ");
        candidate = candidate.replaceAll("(?i)(在|下|里|目录下|路径下|相关的|相关|有关的|有关|一下|帮忙|帮我|请帮我|找|查|搜索|前几个|前几项|前几条|个文件|个)", " ");
        candidate = candidate.replaceAll("\\s+", " ").trim();
        return candidate;
    }

    private List<String> tokenizeInput(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : input.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "./";
        }
        String normalized = stripQuotes(path.trim());
        return normalized.isBlank() ? "./" : normalized;
    }

    private String normalizeFileType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (FILE_TYPE_ALIASES.containsKey(normalized)) {
            return FILE_TYPE_ALIASES.get(normalized);
        }
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String normalized = stripQuotes(keyword.trim())
                .replaceAll("^(?:里|下|中)\\s*", "")
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return 3;
        }
        String normalized = raw.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Math.max(1, Math.min(8, Integer.parseInt(normalized)));
        }
        return switch (normalized) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "十" -> 8;
            default -> 3;
        };
    }

    private String extract(String input, Pattern pattern) {
        if (input == null || input.isBlank() || pattern == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return "";
        }
        return stripQuotes(matcher.group(1));
    }

    private String stripQuotes(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("^[`\"']|[`\"']$", "");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String capitalize(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        if (token.length() == 1) {
            return token.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context == null || context.attributes() == null ? null : context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private record SearchRequest(String path, String keyword, String fileType, int limit) {
    }
}
