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
        return "根据任务描述生成代码草稿，可附带语言或风格偏好。";
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
        String output = "我先给你一个可落地的代码起步方案：\n"
                + "- 任务目标：" + taskDescription + "\n"
                + "- 下一步：告诉我你希望的语言、框架或输入输出示例，我会直接补成完整代码。";
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
