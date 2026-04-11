package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultEvaluatorAgentTest {

    @Test
    void shouldBuildSuccessEvaluationFromTrace() {
        AutonomousGoal goal = new AutonomousGoal(
                "goal-1",
                AutonomousGoalType.MEMORY_REVIEW,
                "整理最近对话",
                "总结最近对话并提炼记忆",
                "llm.orchestrate",
                "memory-review",
                80,
                Map.of(),
                List.of("recent turns"),
                Instant.now()
        );
        MasterOrchestrationResult result = new MasterOrchestrationResult(
                SkillResult.success("llm.orchestrate", "完成总结"),
                new ExecutionTraceDto(
                        "multi-agent-master",
                        0,
                        new CritiqueReportDto(true, "目标已完成", "learn-procedure"),
                        List.of(new PlanStepDto("planner", "success", "planner-agent", "ok", Instant.now(), Instant.now()))
                ),
                List.of(),
                Map.of()
        );

        AutonomousEvaluation evaluation = new DefaultEvaluatorAgent().evaluate(goal, result);

        assertTrue(evaluation.success());
        assertEquals(1.0, evaluation.score());
        assertEquals("目标完成，可固化为流程", evaluation.feedback());
        assertEquals("learn-procedure", evaluation.nextAction());
        assertEquals("llm.orchestrate", evaluation.resultSkill());
    }

    @Test
    void shouldSurfaceFailureReasonAndAction() {
        AutonomousGoal goal = new AutonomousGoal(
                "goal-2",
                AutonomousGoalType.LONG_TASK,
                "推进长期任务",
                "补全 studentId 并继续执行",
                "todo.create",
                "task-1",
                90,
                Map.of(),
                List.of("blocked"),
                Instant.now()
        );
        MasterOrchestrationResult result = new MasterOrchestrationResult(
                SkillResult.failure("todo.create", "missing studentId"),
                new ExecutionTraceDto(
                        "multi-agent-master",
                        1,
                        new CritiqueReportDto(false, "缺少 studentId", "补参后重试"),
                        List.of(new PlanStepDto("executor", "failed", "executor-agent", "missing studentId", Instant.now(), Instant.now()))
                ),
                List.of(),
                Map.of()
        );

        AutonomousEvaluation evaluation = new DefaultEvaluatorAgent().evaluate(goal, result);

        assertFalse(evaluation.success());
        assertEquals(0.0, evaluation.score());
        assertTrue(evaluation.feedback().contains("缺少 studentId"));
        assertEquals("补参后重试", evaluation.nextAction());
        assertTrue(evaluation.reasons().stream().anyMatch(reason -> reason.contains("failedSteps")));
    }

    @Test
    void shouldUseUserFeedbackWhenProvided() {
        AutonomousGoal goal = new AutonomousGoal(
                "goal-3",
                AutonomousGoalType.MEMORY_REVIEW,
                "优化排课效率",
                "根据历史行为优化排课流程",
                "llm.orchestrate",
                "memory-review",
                75,
                Map.of(),
                List.of("behavior"),
                Instant.now()
        );
        MasterOrchestrationResult result = new MasterOrchestrationResult(
                SkillResult.success("llm.orchestrate", "完成排课优化建议"),
                new ExecutionTraceDto(
                        "multi-agent-master",
                        0,
                        new CritiqueReportDto(true, "目标完成", "learn-procedure"),
                        List.of()
                ),
                List.of(),
                Map.of()
        );

        AutonomousEvaluation evaluation = new DefaultEvaluatorAgent().evaluate(goal, result, "执行成功，但耗时较长");

        assertTrue(evaluation.success());
        assertEquals(0.85, evaluation.score(), 0.001);
        assertEquals("执行成功，但耗时较长", evaluation.feedback());
        assertTrue(evaluation.reasons().stream().anyMatch(reason -> reason.contains("userFeedback")));
    }
}
