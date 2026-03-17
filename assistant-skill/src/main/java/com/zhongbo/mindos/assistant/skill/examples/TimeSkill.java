package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class TimeSkill implements Skill {
    private static final Logger LOGGER = Logger.getLogger(TimeSkill.class.getName());
    private final LlmClient llmClient;

    public TimeSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public TimeSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "Returns the current server time.";
    }

    @Override
    public boolean supports(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.toLowerCase();
        return normalized.contains("time") || normalized.contains("clock");
    }

    @Override
    public SkillResult run(SkillContext context) {
        if (llmClient != null) {
            try {
                String prompt = "你是一个时间助手，请用自然语言回答当前时间，仅输出文本。";
                String llmReply = llmClient.generateResponse(prompt, buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for time skill, fallback to local output", ex);
            }
        }
        return SkillResult.success(name(), "Current time is " + ZonedDateTime.now());
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }
}
