package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class FileSearchSkill implements Skill, SkillDescriptorProvider {
    private final FileSearchSkillExecutor executor;

    public FileSearchSkill(LlmClient llmClient) {
        this.executor = new FileSearchSkillExecutor(llmClient);
    }

    public FileSearchSkill() {
        this(null);
    }

    @Override
    public String name() {
        return executor.name();
    }

    @Override
    public String description() {
        return executor.description();
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return executor.skillDescriptor();
    }

    @Override
    public SkillResult run(SkillContext context) {
        return executor.execute(context);
    }
}

final class FileSearchSkillExecutor {
    private static final Logger LOGGER = Logger.getLogger(FileSearchSkill.class.getName());
    private final LlmClient llmClient;

    FileSearchSkillExecutor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    String name() {
        return "file.search";
    }

    String description() {
        return "按路径和关键词整理候选文件，适合快速缩小排查范围。";
    }

    SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("找文件", "查文件", "搜索文件", "search file", "grep", "目录", "路径"));
    }

    SkillResult execute(SkillContext context) {
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
        Map<String, Object> llmContext = new java.util.LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private SearchRequest resolveRequest(SkillContext context) {
        Map<String, Object> resolved = attributes(context);
        return new SearchRequest(
                String.valueOf(resolved.getOrDefault("path", "./")),
                String.valueOf(resolved.getOrDefault("keyword", "")),
                String.valueOf(resolved.getOrDefault("fileType", resolved.getOrDefault("type", ""))),
                asInt(resolved.get("limit"), 5)
        );
    }

    private Map<String, Object> attributes(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return Map.of();
        }
        return context.attributes();
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
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

    private String capitalize(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        if (token.length() == 1) {
            return token.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    private record SearchRequest(String path, String keyword, String fileType, int limit) {
    }
}
