package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class CodeGenerateSkill implements Skill, SkillDescriptorProvider {
    private final CodeGenerateSkillExecutor executor;

    @Autowired
    public CodeGenerateSkill(LlmClient llmClient,
                             @Value("${mindos.skill.code-generate.llm-provider:gpt}") String defaultProvider,
                             @Value("${mindos.skill.code-generate.model.easy:}") String easyModel,
                             @Value("${mindos.skill.code-generate.model.medium:}") String mediumModel,
                             @Value("${mindos.skill.code-generate.model.hard:}") String hardModel) {
        this.executor = new CodeGenerateSkillExecutor(llmClient, defaultProvider, easyModel, mediumModel, hardModel);
    }

    public CodeGenerateSkill(LlmClient llmClient) {
        this(llmClient, "gpt", null, null, null);
    }

    public CodeGenerateSkill() {
        this(null, "gpt", null, null, null);
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

final class CodeGenerateSkillExecutor {
    private static final Logger LOGGER = Logger.getLogger(CodeGenerateSkill.class.getName());
    private final LlmClient llmClient;
    private final String defaultProvider;
    private final String easyModel;
    private final String mediumModel;
    private final String hardModel;

    CodeGenerateSkillExecutor(LlmClient llmClient,
                              String defaultProvider,
                              String easyModel,
                              String mediumModel,
                              String hardModel) {
        this.llmClient = llmClient;
        this.defaultProvider = normalizeOptional(defaultProvider);
        this.easyModel = normalizeOptional(easyModel);
        this.mediumModel = normalizeOptional(mediumModel);
        this.hardModel = normalizeOptional(hardModel);
    }

    String name() {
        return "code.generate";
    }

    String description() {
        return "根据任务描述生成代码草稿，可附带语言或风格偏好。";
    }

    SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("generate code", "代码", "生成代码", "写代码", "接口", "api", "dto", "controller", "bug", "修复", "sql"));
    }

    SkillResult execute(SkillContext context) {
        Map<String, Object> resolved = attributes(context);
        String taskDescription = text(resolved.get("task"));
        if (taskDescription == null || taskDescription.isBlank()) {
            return SkillResult.failure(name(), "请提供 task 参数，我才能生成对应代码。");
        }
        String style = text(resolved.get("style"));
        String language = text(resolved.get("language"));
        if (llmClient != null) {
            try {
                StringBuilder prompt = new StringBuilder("你是一个代码生成助手，请根据如下任务描述生成代码，仅输出代码内容。任务描述：")
                        .append(taskDescription);
                if (!style.isBlank()) {
                    prompt.append("。编码风格偏好：").append(style);
                }
                if (!language.isBlank()) {
                    prompt.append("。输出语言偏好：").append(language);
                }
                String llmReply = llmClient.generateResponse(prompt.toString(), buildLlmContext(context, taskDescription));
                if (isUsableLlmReply(llmReply)) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for code.generate skill, fallback to local output", ex);
            }
        }
        String output = "我先给你一个可落地的代码起步方案：\n"
                + "- 任务目标：" + taskDescription + "\n"
                + "- 下一步：告诉我你希望的语言、框架或输入输出示例，我会直接补成完整代码。";
        return SkillResult.success(name(), output);
    }

    private Map<String, Object> buildLlmContext(SkillContext context, String taskDescription) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        String provider = asString(context, "llmProvider", defaultProvider == null ? "gpt" : defaultProvider);
        if (provider != null && !provider.isBlank()) {
            llmContext.put("llmProvider", provider);
        }

        String explicitModel = asString(context, "model", "");
        if (explicitModel == null || explicitModel.isBlank()) {
            explicitModel = asString(context, "llmModel", "");
        }
        String selectedModel = explicitModel == null || explicitModel.isBlank()
                ? modelByDifficulty(estimateDifficulty(taskDescription))
                : explicitModel.trim();
        if (selectedModel != null && !selectedModel.isBlank()) {
            llmContext.put("model", selectedModel);
        }
        return llmContext;
    }

    private Map<String, Object> attributes(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return Map.of();
        }
        return context.attributes();
    }

    private String modelByDifficulty(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> firstNonBlank(easyModel, mediumModel, hardModel);
            case MEDIUM -> firstNonBlank(mediumModel, hardModel, easyModel);
            case HARD -> firstNonBlank(hardModel, mediumModel, easyModel);
        };
    }

    private Difficulty estimateDifficulty(String taskDescription) {
        if (taskDescription == null) {
            return Difficulty.EASY;
        }
        String normalized = taskDescription.trim().toLowerCase();
        int score = 0;
        if (normalized.length() > 220) {
            score += 2;
        }
        if (normalized.contains("\n") || normalized.contains("```") || normalized.contains("stacktrace")) {
            score += 1;
        }
        if (containsAny(normalized,
                "架构", "重构", "优化", "并发", "性能", "transaction", "分布式", "设计模式", "sql")) {
            score += 2;
        }
        if (containsAny(normalized,
                "实现", "接口", "api", "debug", "修复", "测试", "方案")) {
            score += 1;
        }

        if (score >= 3) {
            return Difficulty.HARD;
        }
        if (score <= 0) {
            return Difficulty.EASY;
        }
        return Difficulty.MEDIUM;
    }

    private boolean containsAny(String normalized, String... terms) {
        for (String term : terms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isUsableLlmReply(String llmReply) {
        return llmReply != null && !llmReply.isBlank() && !llmReply.startsWith("[LLM ");
    }

    private enum Difficulty {
        EASY,
        MEDIUM,
        HARD
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context == null || context.attributes() == null ? null : context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }
}
