package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.command.TodoCreateCommandSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoCreateSkillTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-09T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final TodoCreateSkill skill = new TodoCreateSkill(null, fixedClock);

    @Test
    void shouldInferTaskDueDatePriorityAndReminderFromNaturalLanguage() {
        SkillResult result = skill.run(todoContext(
                "u1",
                "提醒我明天下午5点前提交周报，高优先级，提前30分钟提醒",
                Map.of("timezone", "Asia/Shanghai")
        ));

        assertTrue(result.success());
        assertTrue(result.output().contains("待办：提交周报"));
        assertTrue(result.output().contains("截止：2026-04-10 17:00"));
        assertTrue(result.output().contains("优先级：高"));
        assertTrue(result.output().contains("提醒：提前30分钟提醒"));
        assertTrue(result.output().contains("时区：Asia/Shanghai"));
    }

    @Test
    void shouldSupportWeekdayInference() {
        SkillResult result = skill.run(todoContext(
                "u1",
                "帮我记一下下周三完成季度复盘",
                Map.of("timezone", "Asia/Shanghai")
        ));

        assertTrue(result.success());
        assertTrue(result.output().contains("待办：季度复盘"));
        assertTrue(result.output().contains("截止：2026-04-15"));
    }

    @Test
    void shouldFailWhenTaskCannotBeInferred() {
        SkillResult result = skill.run(todoContext("u1", "   ", Map.of()));

        assertFalse(result.success());
        assertTrue(result.output().contains("具体待办事项"));
    }

    private SkillContext todoContext(String userId, String input, Map<String, Object> extraAttributes) {
        TodoCreateCommandSupport commandSupport = new TodoCreateCommandSupport(fixedClock);
        SkillContext raw = new SkillContext(userId, input, extraAttributes);
        Map<String, Object> attributes = new LinkedHashMap<>(commandSupport.resolveAttributes(raw));
        attributes.putAll(extraAttributes);
        return new SkillContext(userId, input, attributes);
    }
}
