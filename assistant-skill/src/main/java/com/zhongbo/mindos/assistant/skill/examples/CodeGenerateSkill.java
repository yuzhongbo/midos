package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class CodeGenerateSkill implements Skill {
    private static final Logger LOGGER = Logger.getLogger(CodeGenerateSkill.class.getName());
    private final LlmClient llmClient;

    public CodeGenerateSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public CodeGenerateSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "code.generate";
    }

    @Override
    public String description() {
        return "Generates code from a task description (skeleton placeholder).";
    }

    @Override
    public SkillResult run(SkillContext context) {
        String taskDescription = asString(context, "task", context.input());
        String style = asString(context, "style", "");
        String language = asString(context, "language", "");
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
                String llmReply = llmClient.generateResponse(prompt.toString(), buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for code.generate skill, fallback to local output", ex);
            }
        }
        String output = "[code.generate] Placeholder generated code for task: " + taskDescription;
        return SkillResult.success(name(), output);
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private String asString(SkillContext context, String key, String defaultValue) {
        Object value = context.attributes().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
