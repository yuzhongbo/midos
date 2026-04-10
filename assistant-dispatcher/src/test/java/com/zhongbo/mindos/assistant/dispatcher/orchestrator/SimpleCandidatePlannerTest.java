package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
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
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDslExecutor;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleCandidatePlannerTest {

    @Test
    void shouldRankCandidatesByKeywordMemoryAndSuccessRate() {
        SkillEngine skillEngine = new SkillEngine(
                new SkillRegistry(List.of(
                        scoredSkill("mcp.qwensearch.webSearch", 930),
                        scoredSkill("mcp.bravesearch.webSearch", 900),
                        scoredSkill("mcp.serper.webSearch", 880),
                        scoredSkill("mcp.serpapi.webSearch", 860)
                )),
                new SkillDslExecutor(new SkillRegistry(List.of(
                        scoredSkill("mcp.qwensearch.webSearch", 930),
                        scoredSkill("mcp.bravesearch.webSearch", 900),
                        scoredSkill("mcp.serper.webSearch", 880),
                        scoredSkill("mcp.serpapi.webSearch", 860)
                )))
        );
        MemoryGateway memoryGateway = gateway(
                List.of(),
                List.of(
                        new SkillUsageStats("mcp.bravesearch.webSearch", 10, 10, 0),
                        new SkillUsageStats("mcp.serper.webSearch", 6, 5, 1),
                        new SkillUsageStats("mcp.qwensearch.webSearch", 1, 0, 1)
                )
        );
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new MemoryNode("skill:qwen", "result.skill", Map.of("name", "Qwen Search", "skillName", "mcp.qwensearch.webSearch", "topic", "今天新闻"), null, null));
        SimpleCandidatePlanner planner = new SimpleCandidatePlanner(skillEngine, memoryGateway, graphMemory, 3, 0.40, 0.35, 0.15, 0.10);

        List<ScoredCandidate> candidates = planner.plan(
                "",
                new DecisionOrchestrator.OrchestrationRequest("u1", "今天新闻", new SkillContext("u1", "今天新闻", Map.of()), Map.of())
        );

        assertEquals(3, candidates.size());
        assertEquals("mcp.bravesearch.webSearch", candidates.get(0).skillName());
        assertTrue(candidates.get(0).reasons().stream().anyMatch(reason -> reason.contains("successRate")));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.reasons().stream().anyMatch(reason -> reason.contains("graphScore"))));
    }

    @Test
    void shouldPreferLowerCostCandidateWhenCapabilityTies() {
        SkillEngine skillEngine = new SkillEngine(
                new SkillRegistry(List.of(
                        scoredSkill("skill.fast", 900),
                        scoredSkill("skill.slow", 900)
                )),
                new SkillDslExecutor(new SkillRegistry(List.of(
                        scoredSkill("skill.fast", 900),
                        scoredSkill("skill.slow", 900)
                )))
        );
        MemoryGateway memoryGateway = gateway(
                List.of(),
                List.of(
                        new SkillUsageStats("skill.fast", 4, 4, 0),
                        new SkillUsageStats("skill.slow", 4, 4, 0)
                )
        );
        GraphMemory graphMemory = new GraphMemory();
        SkillCostTelemetry telemetry = new SkillCostTelemetry() {
            @Override
            public void record(String userId, String skillName, long latencyMs, int totalTokensEstimate, boolean success) {
            }

            @Override
            public Map<String, CostModel> costModels(String userId) {
                return Map.of(
                        "skill.fast", new CostModel(0.10, 0.10, 1.0),
                        "skill.slow", new CostModel(0.90, 0.90, 1.0)
                );
            }
        };
        SimpleCandidatePlanner planner = new SimpleCandidatePlanner(skillEngine, memoryGateway, graphMemory, telemetry, 3, 0.40, 0.35, 0.15, 0.10);

        List<ScoredCandidate> candidates = planner.plan(
                "",
                new DecisionOrchestrator.OrchestrationRequest("u1", "cost sensitive search", new SkillContext("u1", "cost sensitive search", Map.of()), Map.of())
        );

        assertEquals(2, candidates.size());
        assertEquals("skill.fast", candidates.get(0).skillName());
        assertTrue(candidates.get(0).reasons().stream().anyMatch(reason -> reason.contains("cost=")));
    }

    private Skill scoredSkill(String name, int routingScore) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public SkillResult run(SkillContext context) {
                return SkillResult.success(name, name);
            }

            @Override
            public int routingScore(String input) {
                return routingScore;
            }
        };
    }

    private MemoryGateway gateway(List<ConversationTurn> history, List<SkillUsageStats> stats) {
        return new MemoryGateway() {
            @Override
            public List<ConversationTurn> recentHistory(String userId) {
                return history;
            }

            @Override
            public List<SkillUsageStats> skillUsageStats(String userId) {
                return stats;
            }

            @Override
            public void appendUserConversation(String userId, String message) {
            }

            @Override
            public void appendAssistantConversation(String userId, String message) {
            }

            @Override
            public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
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
                return PreferenceProfile.empty();
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
    }
}
