package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.SharedMemorySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultReflectionAgentTest {

    @Test
    void shouldClassifyMissingParameterAndWriteMemory() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(List.of(), List.of());
        DefaultReflectionAgent agent = new DefaultReflectionAgent(memoryGateway, "autonomous.reflection", "reflection");

        ReflectionResult result = agent.reflect(ReflectionRequest.of(
                "u-1",
                "帮我排课",
                new ExecutionTraceDto(
                        "single-pass",
                        0,
                        new CritiqueReportDto(false, "参数缺失导致失败", "补全 studentId"),
                        List.of(new PlanStepDto("validate", "failed", "planner", "missing studentId", Instant.now(), Instant.now())),
                        new RoutingDecisionDto("REMOTE", "schedule.optimize", 0.82, List.of("param-check"), List.of())
                ),
                SkillResult.failure("schedule.optimize", "missing studentId"),
                Map.of("studentId", "", "courseId", "math-101"),
                Map.of()
        ));

        assertEquals("missing_param", result.pattern());
        assertTrue(result.rootCause().contains("studentId"));
        assertTrue(result.improvement().contains("ParamValidator"));
        assertEquals(1, memoryGateway.proceduralEntries.size());
        assertEquals(1, memoryGateway.semanticTexts.size());
        assertEquals("autonomous.reflection", memoryGateway.semanticBuckets.get(0));
        assertFalse(result.dimensionScores().isEmpty());
    }

    @Test
    void shouldClassifySkillSelectionIssue() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(List.of(), List.of());
        DefaultReflectionAgent agent = new DefaultReflectionAgent(memoryGateway, "autonomous.reflection", "reflection");

        ReflectionResult result = agent.reflect(ReflectionRequest.of(
                "u-2",
                "执行课程优化",
                new ExecutionTraceDto(
                        "route-first",
                        0,
                        new CritiqueReportDto(false, "路由命中错误 skill", "切换 planner-agent"),
                        List.of(),
                        new RoutingDecisionDto("MCP", "planner-agent", 0.74, List.of("fallback", "skill mismatch"), List.of())
                ),
                SkillResult.failure("executor-agent", "unsupported"),
                Map.of("studentId", "stu-9"),
                Map.of(
                        SharedMemorySnapshot.CONTEXT_KEY,
                        new SharedMemorySnapshot("u-2", List.of(), List.of(), List.of(), List.of(), Map.of("persona", "present"), Map.of())
                )
        ));

        assertEquals("skill_selection", result.pattern());
        assertTrue(result.rootCause().contains("planner-agent"));
        assertTrue(result.rootCause().contains("executor-agent"));
        assertTrue(result.improvement().contains("Skill/Router"));
    }

    @Test
    void shouldClassifyMemoryGap() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(List.of(), List.of());
        DefaultReflectionAgent agent = new DefaultReflectionAgent(memoryGateway, "autonomous.reflection", "reflection");

        ReflectionResult result = agent.reflect(ReflectionRequest.of(
                "u-3",
                "根据记忆调整策略",
                new ExecutionTraceDto(
                        "context-check",
                        0,
                        new CritiqueReportDto(false, "记忆上下文不足", "先补齐 persona / semantic / procedural"),
                        List.of(),
                        new RoutingDecisionDto("REMOTE", "llm.orchestrate", 0.66, List.of("memory", "context"), List.of())
                ),
                SkillResult.failure("llm.orchestrate", "need more context"),
                Map.of("intent", "optimize"),
                Map.of()
        ));

        assertEquals("memory_gap", result.pattern());
        assertTrue(result.rootCause().contains("记忆上下文不足"));
        assertTrue(result.improvement().contains("Persona / Semantic / Procedural Memory"));
    }

    @Test
    void shouldClassifySchedulingIssue() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(List.of(), List.of());
        DefaultReflectionAgent agent = new DefaultReflectionAgent(memoryGateway, "autonomous.reflection", "reflection");

        ReflectionResult result = agent.reflect(ReflectionRequest.of(
                "u-4",
                "执行 DAG",
                new ExecutionTraceDto(
                        "dag-run",
                        3,
                        new CritiqueReportDto(false, "出现阻塞", "replan"),
                        List.of(
                                new PlanStepDto("step-a", "success", "planner", "ok", Instant.now(), Instant.now()),
                                new PlanStepDto("step-b", "blocked", "executor", "等待依赖", Instant.now(), Instant.now()),
                                new PlanStepDto("step-c", "failed", "executor", "retry later", Instant.now(), Instant.now())
                        ),
                        new RoutingDecisionDto("LOCAL", "executor-agent", 0.55, List.of("replan", "blocked"), List.of())
                ),
                SkillResult.failure("executor-agent", "blocked"),
                Map.of("taskId", "task-1"),
                Map.of(
                        SharedMemorySnapshot.CONTEXT_KEY,
                        new SharedMemorySnapshot("u-4", List.of(), List.of(), List.of(), List.of(), Map.of("persona", "present"), Map.of())
                )
        ));

        assertEquals("scheduling", result.pattern());
        assertTrue(result.rootCause().contains("阻塞"));
        assertTrue(result.improvement().contains("DAG"));
    }
}
