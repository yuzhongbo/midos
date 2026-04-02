package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class TodoCreateSkill implements Skill {
    private static final Logger LOGGER = Logger.getLogger(TodoCreateSkill.class.getName());
    private final LlmClient llmClient;

    public TodoCreateSkill(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public TodoCreateSkill() {
        this(null);
    }

    @Override
    public String name() {
        return "todo.create";
    }

    @Override
    public String description() {
        return "根据任务描述和截止时间生成待办事项，适合快速记任务。";
    }

    @Override
    public List<String> routingKeywords() {
        return List.of("待办", "todo", "提醒", "安排任务", "创建任务", "截止", "deadline");
    }

    @Override
    public SkillResult run(SkillContext context) {
        String task = asString(context, "task", "Untitled task");
        String dueDate = asString(context, "dueDate", "unspecified");
        String style = asString(context, "style", "");
        String timezone = asString(context, "timezone", "");
        if (llmClient != null) {
            try {
                StringBuilder prompt = new StringBuilder("你是一个待办事项助手，请根据如下任务和截止日期生成简洁 todo 事项描述，仅输出文本。任务：")
                        .append(task)
                        .append(", 截止日期：")
                        .append(dueDate);
                if (!style.isBlank()) {
                    prompt.append("。执行风格偏好：").append(style);
                }
                if (!timezone.isBlank()) {
                    prompt.append("。时区偏好：").append(timezone);
                }
                String llmReply = llmClient.generateResponse(prompt.toString(), buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for todo.create skill, fallback to local output", ex);
            }
        }
        String output = "好的，我先帮你记下这件事：\n"
                + "- 待办：" + task + "\n"
                + "- 截止：" + dueDate + "\n"
                + "如果你愿意，我可以继续帮你拆成今天/本周的执行步骤。";
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
