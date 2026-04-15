package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Component
public class TodoCreateSkill implements Skill, SkillDescriptorProvider {
    private final TodoCreateSkillExecutor executor;

    @Autowired
    public TodoCreateSkill(LlmClient llmClient) {
        this(llmClient, Clock.systemDefaultZone());
    }

    TodoCreateSkill(LlmClient llmClient, Clock clock) {
        this.executor = new TodoCreateSkillExecutor(llmClient, clock);
    }

    public TodoCreateSkill() {
        this(null, Clock.systemDefaultZone());
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

final class TodoCreateSkillExecutor {
    TodoCreateSkillExecutor(LlmClient llmClient, Clock clock) {
    }

    String name() {
        return "todo.create";
    }

    String description() {
        return "根据任务描述和截止时间生成待办事项，适合快速记任务。";
    }

    SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("待办", "todo", "提醒", "安排任务", "创建任务", "截止", "deadline"));
    }

    SkillResult execute(SkillContext context) {
        TodoDraft draft = resolveDraft(context);
        if (draft.task().isBlank()) {
            return SkillResult.failure(name(), "请告诉我要记录的具体待办事项。");
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

    private TodoDraft resolveDraft(SkillContext context) {
        Map<String, Object> resolved = attributes(context);
        String timezone = asString(resolved.get("timezone"));
        String task = asString(resolved.get("task"));
        String dueDate = firstNonBlank(asString(resolved.get("dueDate")), "未指定");
        String priority = asString(resolved.get("priority"));
        String reminder = asString(resolved.get("reminder"));
        String style = asString(resolved.get("style"));
        return new TodoDraft(task, dueDate, priority, reminder, timezone, style);
    }

    private Map<String, Object> attributes(SkillContext context) {
        if (context == null || context.attributes() == null) {
            return Map.of();
        }
        return context.attributes();
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
