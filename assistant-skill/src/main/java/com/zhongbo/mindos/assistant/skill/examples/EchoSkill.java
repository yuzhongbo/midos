package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class EchoSkill implements Skill, SkillDescriptorProvider {
    private static final Logger LOGGER = Logger.getLogger(EchoSkill.class.getName());
    private final LlmClient llmClient;

    @Autowired
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
        return "复述你提供的文本，适合快速确认输入或做简短回显。";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("echo"));
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
                if (isUsableLlmReply(llmReply)) {
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
        if (context == null || context.input() == null) {
            return null;
        }
        String input = context.input().trim();
        if (!input.regionMatches(true, 0, "echo ", 0, "echo ".length())) {
            return input.isBlank() ? null : input;
        }
        String echoed = input.length() <= "echo ".length() ? "" : input.substring("echo ".length()).trim();
        return echoed.isBlank() ? null : echoed;
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private boolean isUsableLlmReply(String llmReply) {
        return llmReply != null && !llmReply.isBlank() && !llmReply.startsWith("[LLM ");
    }
}
