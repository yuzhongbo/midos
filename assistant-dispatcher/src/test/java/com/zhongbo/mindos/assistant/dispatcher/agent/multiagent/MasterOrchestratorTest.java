package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.InMemoryProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.runtime.System2Planner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.DefaultSkillExecutionGateway;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterOrchestratorTest {

    @Test
    void shouldCoordinatePlannerExecutorMemoryAndToolAgents() {
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new MemoryNode(
                "entity:student:42",
                "entity.student",
                Map.of("studentId", "stu-42", "name", "Alice"),
                null,
                null
        ));

        List<String> skillUsageEvents = new ArrayList<>();
        List<String> assistantMessages = new ArrayList<>();
        MemoryGateway memoryGateway = new MemoryGateway() {
            @Override
            public List<ConversationTurn> recentHistory(String userId) {
                return List.of(ConversationTurn.user("帮我生成学生计划"));
            }

            @Override
            public List<SkillUsageStats> skillUsageStats(String userId) {
                return List.of();
            }

            @Override
            public void appendUserConversation(String userId, String message) {
            }

            @Override
            public void appendAssistantConversation(String userId, String message) {
                assistantMessages.add(message);
            }

            @Override
            public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
                skillUsageEvents.add(skillName + ":" + success);
            }

            @Override
            public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, SemanticMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
            }

            @Override
            public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
                return profile;
            }

            @Override
            public LongTask createLongTask(String userId, String title, String objective, List<String> steps, Instant dueAt, Instant nextCheckAt) {
                return null;
            }

            @Override
            public LongTask updateLongTaskProgress(String userId, String taskId, String workerId, String completedStep, String note, String blockedReason, Instant nextCheckAt, boolean markCompleted) {
                return null;
            }

            @Override
            public LongTask updateLongTaskStatus(String userId, String taskId, LongTaskStatus status, String note, Instant nextCheckAt) {
                return null;
            }
        };

        ProceduralMemory proceduralMemory = new ProceduralMemory(new InMemoryProcedureMemoryEngine());
        MemoryFacade memoryFacade = new MemoryFacade(graphMemory, null);

        Skill studentGet = new Skill() {
            @Override
            public String name() {
                return "student.get";
            }

            @Override
            public String description() {
                return "student.get";
            }

            @Override
            public SkillResult run(SkillContext context) {
                Object studentId = context.attributes().get("studentId");
                if (studentId == null || String.valueOf(studentId).isBlank()) {
                    return SkillResult.failure(name(), "missing studentId");
                }
                return SkillResult.success(name(), "student:" + studentId);
            }
        };

        Skill studentAnalyze = new Skill() {
            @Override
            public String name() {
                return "student.analyze";
            }

            @Override
            public String description() {
                return "student.analyze";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "analysis:" + context.attributes().get("student"));
            }
        };

        Skill teachingPlan = new Skill() {
            @Override
            public String name() {
                return "teaching.plan";
            }

            @Override
            public String description() {
                return "teaching.plan";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "plan:" + context.attributes().get("analysis"));
            }
        };

        SkillRegistry registry = new SkillRegistry(List.of(studentGet, studentAnalyze, teachingPlan));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultSkillExecutionGateway skillGateway = new DefaultSkillExecutionGateway(registry, dslExecutor);

        TaskGraph taskGraph = new TaskGraph(List.of(
                new TaskNode("fetch", "student.get", Map.of(), List.of(), "student", false),
                new TaskNode("analyze", "student.analyze", Map.of("student", "${task.student.output}"), List.of("fetch"), "analysis", false),
                new TaskNode("plan", "teaching.plan", Map.of("analysis", "${task.analysis.output}"), List.of("analyze"), "plan", false)
        ));

        System2Planner planner = request -> new System2Planner.PlanResult(taskGraph, null, "stub-plan");

        MasterOrchestrator orchestrator = new MasterOrchestrator(
                new DefaultPlannerAgent(planner),
                new DefaultExecutorAgent(),
                new DefaultMemoryAgent(memoryGateway, memoryFacade, proceduralMemory),
                new DefaultToolAgent(skillGateway),
                null,
                new OrchestratorMemoryWriter(new DispatcherMemoryCommandService(memoryGateway, graphMemory, proceduralMemory))
        );

        Decision decision = new Decision("student.plan", "student.plan", Map.of("studentId", ""), 0.62, false);
        MasterOrchestrationResult result = orchestrator.orchestrate(
                decision,
                new com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest(
                        "u1",
                        "帮我生成学生计划",
                        new SkillContext("u1", "帮我生成学生计划", Map.of()),
                        Map.of()
                )
        );

        assertTrue(result.success());
        assertEquals("teaching.plan", result.result().skillName());
        assertEquals("plan:analysis:student:stu-42", result.result().output());
        assertEquals("stu-42", result.sharedState().get("studentId"));
        assertTrue(result.sharedState().get(SharedMemorySnapshot.CONTEXT_KEY) instanceof SharedMemorySnapshot);
        assertTrue(result.transcript().stream().anyMatch(msg -> "memory-agent".equals(msg.to()) && msg.type() == AgentTaskType.MEMORY_READ));
        assertTrue(result.transcript().stream().anyMatch(msg -> "planner-agent".equals(msg.to()) && msg.type() == AgentTaskType.PLAN_REQUEST));
        assertTrue(result.transcript().stream().anyMatch(msg -> "executor-agent".equals(msg.to()) && msg.type() == AgentTaskType.EXECUTE_GRAPH));
        assertTrue(result.transcript().stream().anyMatch(msg -> "tool-agent".equals(msg.to()) && msg.type() == AgentTaskType.TOOL_CALL));
        assertTrue(result.transcript().stream().anyMatch(msg -> "memory-agent".equals(msg.to()) && msg.type() == AgentTaskType.MEMORY_WRITE));
        assertTrue(result.trace().steps().size() >= 8);
        assertFalse(skillUsageEvents.stream().anyMatch(String::isBlank));
        assertFalse(assistantMessages.isEmpty());
        assertTrue(proceduralMemory.matchReusableProcedure("u1", "帮我生成学生计划", "student.plan", Map.of("studentId", "stu-42")).isPresent());
    }

    @Test
    void shouldSkipMemoryWriteWhenRequested() {
        GraphMemory graphMemory = new GraphMemory();
        List<String> skillUsageEvents = new ArrayList<>();
        MemoryGateway memoryGateway = new MemoryGateway() {
            @Override
            public List<ConversationTurn> recentHistory(String userId) {
                return List.of(ConversationTurn.user("帮我生成学生计划"));
            }

            @Override
            public List<SkillUsageStats> skillUsageStats(String userId) {
                return List.of();
            }

            @Override
            public void appendUserConversation(String userId, String message) {
            }

            @Override
            public void appendAssistantConversation(String userId, String message) {
            }

            @Override
            public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
                skillUsageEvents.add(skillName + ":" + success);
            }

            @Override
            public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, SemanticMemoryEntry entry) {
            }

            @Override
            public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
            }

            @Override
            public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
                return profile;
            }

            @Override
            public LongTask createLongTask(String userId, String title, String objective, List<String> steps, Instant dueAt, Instant nextCheckAt) {
                return null;
            }

            @Override
            public LongTask updateLongTaskProgress(String userId, String taskId, String workerId, String completedStep, String note, String blockedReason, Instant nextCheckAt, boolean markCompleted) {
                return null;
            }

            @Override
            public LongTask updateLongTaskStatus(String userId, String taskId, LongTaskStatus status, String note, Instant nextCheckAt) {
                return null;
            }
        };

        ProceduralMemory proceduralMemory = new ProceduralMemory(new InMemoryProcedureMemoryEngine());
        MemoryFacade memoryFacade = new MemoryFacade(graphMemory, null);

        Skill studentGet = new Skill() {
            @Override
            public String name() {
                return "student.get";
            }

            @Override
            public String description() {
                return "student.get";
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name(), "student:" + context.attributes().get("studentId"));
            }
        };

        SkillRegistry registry = new SkillRegistry(List.of(studentGet));
        SkillDslExecutor dslExecutor = new SkillDslExecutor(registry);
        DefaultSkillExecutionGateway skillGateway = new DefaultSkillExecutionGateway(registry, dslExecutor);

        TaskGraph taskGraph = new TaskGraph(List.of(
                new TaskNode("fetch", "student.get", Map.of("studentId", "stu-42"), List.of(), "student", false)
        ));

        System2Planner planner = request -> new System2Planner.PlanResult(taskGraph, null, "stub-plan");
        MasterOrchestrator orchestrator = new MasterOrchestrator(
                new DefaultPlannerAgent(planner),
                new DefaultExecutorAgent(),
                new DefaultMemoryAgent(memoryGateway, memoryFacade, proceduralMemory),
                new DefaultToolAgent(skillGateway),
                null,
                new OrchestratorMemoryWriter(new DispatcherMemoryCommandService(memoryGateway, graphMemory, proceduralMemory))
        );

        Decision decision = new Decision("student.plan", "student.plan", Map.of("studentId", "stu-42"), 0.62, false);
        MasterOrchestrationResult result = orchestrator.orchestrate(
                decision,
                new com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest(
                        "u1",
                        "帮我生成学生计划",
                        new SkillContext("u1", "帮我生成学生计划", Map.of()),
                        Map.of("multiAgent.skipMemoryWrite", true)
                )
        );

        assertTrue(result.success());
        assertEquals(0, skillUsageEvents.size());
        long memoryWriteCount = result.transcript().stream()
                .filter(msg -> "memory-agent".equals(msg.to()) && msg.type() == AgentTaskType.MEMORY_WRITE)
                .count();
        assertEquals(1L, memoryWriteCount);
        long procedureWriteCount = result.transcript().stream()
                .filter(msg -> "memory-agent".equals(msg.to()) && msg.type() == AgentTaskType.MEMORY_WRITE)
                .filter(msg -> "procedure".equalsIgnoreCase(String.valueOf(msg.payloadMap().get("kind"))))
                .count();
        assertEquals(0L, procedureWriteCount);
    }
}
