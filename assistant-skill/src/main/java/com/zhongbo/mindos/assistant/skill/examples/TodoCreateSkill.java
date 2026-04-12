package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.command.TodoCreateCommandSupport;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class TodoCreateSkill implements Skill, SkillDescriptorProvider {
    private static final Logger LOGGER = Logger.getLogger(TodoCreateSkill.class.getName());
    private final LlmClient llmClient;
    private final TodoCreateCommandSupport commandSupport;

    public TodoCreateSkill(LlmClient llmClient) {
        this(llmClient, Clock.systemDefaultZone());
    }

    TodoCreateSkill(LlmClient llmClient, Clock clock) {
        this.llmClient = llmClient;
        this.commandSupport = new TodoCreateCommandSupport(clock == null ? Clock.systemDefaultZone() : clock);
    }

    public TodoCreateSkill() {
        this(null, Clock.systemDefaultZone());
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
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("待办", "todo", "提醒", "安排任务", "创建任务", "截止", "deadline"));
    }

    @Override
    public SkillResult run(SkillContext context) {
        TodoDraft draft = resolveDraft(context);
        if (draft.task().isBlank()) {
            return SkillResult.failure(name(), "请告诉我要记录的具体待办事项。");
        }
        if (llmClient != null) {
            try {
                StringBuilder prompt = new StringBuilder("你是一个待办事项助手，请根据如下任务和截止日期生成简洁 todo 事项描述，仅输出文本。任务：")
                        .append(draft.task())
                        .append(", 截止日期：")
                        .append(draft.dueDate());
                if (!draft.priority().isBlank()) {
                    prompt.append("。优先级：").append(draft.priority());
                }
                if (!draft.reminder().isBlank()) {
                    prompt.append("。提醒：").append(draft.reminder());
                }
                if (!draft.style().isBlank()) {
                    prompt.append("。执行风格偏好：").append(draft.style());
                }
                if (!draft.timezone().isBlank()) {
                    prompt.append("。时区偏好：").append(draft.timezone());
                }
                String llmReply = llmClient.generateResponse(prompt.toString(), buildLlmContext(context));
                if (llmReply != null && !llmReply.isBlank()) {
                    return SkillResult.success(name(), llmReply.trim());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "LLM call failed for todo.create skill, fallback to local output", ex);
            }
        }
        StringBuilder output = new StringBuilder("好的，我先帮你记下这件事：\n");
        output.append("- 待办：").append(draft.task()).append("\n");
        output.append("- 截止：").append(draft.dueDate()).append("\n");
        if (!draft.priority().isBlank()) {
            output.append("- 优先级：").append(draft.priority()).append("\n");
        }
        if (!draft.reminder().isBlank()) {
            output.append("- 提醒：").append(draft.reminder()).append("\n");
        }
        if (!draft.timezone().isBlank()) {
            output.append("- 时区：").append(draft.timezone()).append("\n");
        }
        output.append("如果你愿意，我可以继续帮你拆成今天/本周的执行步骤。");
        return SkillResult.success(name(), output.toString());
    }

    private Map<String, Object> buildLlmContext(SkillContext context) {
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", context.userId() == null ? "" : context.userId());
        llmContext.put("channel", name());
        return llmContext;
    }

    private TodoDraft resolveDraft(SkillContext context) {
        Map<String, Object> resolved = commandSupport.resolveAttributes(context);
        String timezone = asString(resolved.get("timezone"));
        String task = asString(resolved.get("task"));
        String dueDate = firstNonBlank(asString(resolved.get("dueDate")), "未指定");
        String priority = asString(resolved.get("priority"));
        String reminder = asString(resolved.get("reminder"));
        String style = asString(resolved.get("style"));
        return new TodoDraft(task, dueDate, priority, reminder, timezone, style);
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

    private String asString(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private record TodoDraft(String task,
                             String dueDate,
                             String priority,
                             String reminder,
                             String timezone,
                             String style) {
    }
}
