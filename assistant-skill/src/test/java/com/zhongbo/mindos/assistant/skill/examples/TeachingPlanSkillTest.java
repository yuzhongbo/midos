package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeachingPlanSkillTest {

    private final TeachingPlanSkill skill = new TeachingPlanSkill();

    @Test
    void shouldGenerateTeachingPlanFromAttributes() {
        SkillContext context = new SkillContext(
                "u1",
                "给我一个学习计划",
                Map.of(
                        "studentId", "stu-1",
                        "topic", "数学",
                        "goal", "期末提升15分",
                        "durationWeeks", 6,
                        "weeklyHours", 8,
                        "gradeOrLevel", "高一",
                        "weakTopics", "函数, 概率",
                        "learningStyle", "练习优先"
                )
        );

        SkillResult result = skill.run(context);

        assertTrue(result.success());
        assertEquals("teaching.plan", result.skillName());
        assertTrue(result.output().contains("学生ID: stu-1"));
        assertTrue(result.output().contains("主题: 数学"));
        assertTrue(result.output().contains("目标: 期末提升15分"));
        assertTrue(result.output().contains("周期: 6 周"));
        assertTrue(result.output().contains("每周投入: 8 小时"));
        assertTrue(result.output().contains("薄弱点: 函数、概率"));
        assertTrue(result.output().contains("学习风格: 练习优先"));
    }

    @Test
    void shouldUseLlmPlanWhenSchemaIsValid() {
        TeachingPlanSkill llmSkill = new TeachingPlanSkill((prompt, context) -> "{"
                + "\"summary\":\"面向考试的分阶段提分计划\","
                + "\"coursePath\":[{\"phase\":\"第1-2周\",\"focus\":[\"函数\"],\"weeklyHours\":6,\"milestones\":[\"单元测>=80\"]}],"
                + "\"weeklyPlan\":[{\"week\":1,\"tasks\":[{\"subject\":\"数学\",\"topic\":\"函数基础\",\"durationMin\":90,\"type\":\"讲解+练习\"}]}],"
                + "\"assessment\":{\"kpi\":[\"完成率\"],\"checkpoints\":[\"W2\"]},"
                + "\"riskAlerts\":[{\"level\":\"low\",\"message\":\"保持节奏\"}],"
                + "\"adjustmentRules\":[\"未达标则追加复盘\"]"
                + "}");

        SkillResult result = llmSkill.run(new SkillContext("u1", "数学学习计划", Map.of("topic", "数学")));

        assertTrue(result.success());
        assertTrue(result.output().contains("规划概览: 面向考试的分阶段提分计划"));
        assertTrue(result.output().contains("第 1 周"));
    }

    @Test
    void shouldFallbackWhenLlmPlanSchemaIsInvalid() {
        TeachingPlanSkill llmSkill = new TeachingPlanSkill((prompt, context) -> "{\"bad\":\"payload\"}");

        SkillResult result = llmSkill.run(new SkillContext("u1", "数学学习计划", Map.of("topic", "数学")));

        assertTrue(result.success());
        assertTrue(result.output().contains("规划概览: 以数学为主线"));
    }

    @Test
    void shouldFallbackWhenLlmThrowsAndUserIdIsNull() {
        TeachingPlanSkill llmSkill = new TeachingPlanSkill((prompt, context) -> {
            assertEquals("", context.get("userId"));
            throw new RuntimeException("llm down");
        });

        SkillResult result = llmSkill.run(new SkillContext(null, "数学学习计划", Map.of("topic", "数学")));

        assertTrue(result.success());
        assertTrue(result.output().contains("规划概览: 以数学为主线"));
    }

    @Test
    void shouldDetectNaturalLanguageIntent() {
        assertTrue(skill.supports("请给我一个教学规划"));
        assertTrue(skill.supports("need a study plan for java"));
    }
}

