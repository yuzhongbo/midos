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
public class EchoSkill implements Skill {
    private static final Logger LOGGER = Logger.getLogger(EchoSkill.class.getName());
    private final LlmClient llmClient;

    public EchoSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // 兼容无参构造（测试/反射）
    public EchoSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "Echoes back the text after the 'echo' command.";
    }

    @Override
    public boolean supports(String input) {
        return input != null && input.toLowerCase().startsWith("echo ");
    }

    @Override
    public SkillResult run(SkillContext context) {
        String echoedText = resolveEchoText(context);
        if (echoedText == null || echoedText.isBlank()) {
            return SkillResult.failure(name(), "Usage: echo <text>");
        }
        if (llmClient != null) {
            try {
                String prompt = "你是一个智能回声助手，请智能地复述或扩展用户输入内容，仅输出文本。输入：" + echoedText;
                String llmReply = llmClient.generateResponse(prompt, buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for echo skill, fallback to local output", ex);
            }
        }
        return SkillResult.success(name(), echoedText);
    }

    private String resolveEchoText(SkillContext context) {
        Object explicitText = context == null || context.attributes() == null ? null : context.attributes().get("text");
        if (explicitText != null) {
            String normalized = String.valueOf(explicitText).trim();
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        String input = context == null ? null : context.input();
        if (input == null || input.length() <= "echo ".length() || !input.toLowerCase().startsWith("echo ")) {
            return null;
        }
        return input.substring("echo ".length()).trim();
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }
}
