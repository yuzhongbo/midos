package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.InMemoryProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryNode;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryService;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

class BeamSearchCandidatePlannerTest {

    @Test
    void shouldUseKeywordGraphAndProceduralSignals() {
        SkillRegistry registry = new SkillRegistry(List.of(
                skill("student.get", 930),
                skill("student.analyze", 860),
                skill("teaching.plan", 820)
        ));
        SkillEngine skillEngine = new SkillEngine(registry, new SkillDslExecutor(registry));
        GraphMemoryService graphMemory = new GraphMemoryService();
        graphMemory.upsertNode("u1", new GraphMemoryNode("n1", "skill", "student.analyze", Map.of("skillName", "student.analyze"), null, null));

        BeamSearchCandidatePlanner planner = new BeamSearchCandidatePlanner(
                skillEngine,
                new MemoryGateway() {
                    @Override
                    public List<ConversationTurn> recentHistory(String userId) {
                        return List.of();
                    }

                    @Override
                    public List<SkillUsageStats> skillUsageStats(String userId) {
                        return List.of(new SkillUsageStats("teaching.plan", 4, 4, 0));
                    }

                    @Override public void appendUserConversation(String userId, String message) {}
                    @Override public void appendAssistantConversation(String userId, String message) {}
                    @Override public void recordSkillUsage(String userId, String skillName, String input, boolean success) {}
                    @Override public void writeProcedural(String userId, ProceduralMemoryEntry entry) {}
                    @Override public void writeSemantic(String userId, com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry entry) {}
                    @Override public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {}
                    @Override public com.zhongbo.mindos.assistant.memory.model.PreferenceProfile updatePreferenceProfile(String userId, com.zhongbo.mindos.assistant.memory.model.PreferenceProfile profile) { return com.zhongbo.mindos.assistant.memory.model.PreferenceProfile.empty(); }
                    @Override public com.zhongbo.mindos.assistant.memory.model.LongTask createLongTask(String userId, String title, String objective, List<String> steps, Instant dueAt, Instant nextCheckAt) { return null; }
                    @Override public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskProgress(String userId, String taskId, String workerId, String completedStep, String note, String blockedReason, Instant nextCheckAt, boolean markCompleted) { return null; }
                    @Override public com.zhongbo.mindos.assistant.memory.model.LongTask updateLongTaskStatus(String userId, String taskId, com.zhongbo.mindos.assistant.memory.model.LongTaskStatus status, String note, Instant nextCheckAt) { return null; }
                },
                graphMemory,
                new InMemoryProcedureMemoryEngine()
        );

        List<SearchCandidate> candidates = planner.search(new SearchPlanningRequest("u1", "请先获取学生并分析再生成教学计划", "student.plan", Map.of(), 3, 3));

        assertFalse(candidates.isEmpty());
        assertEquals("student.plan", candidates.get(0).path().get(0));
    }

    private Skill skill(String name, int score) {
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
            public SkillResult run(com.zhongbo.mindos.assistant.common.SkillContext context) {
                return SkillResult.success(name, "ok");
            }

            @Override
            public int routingScore(String input) {
                return score;
            }
        };
    }
}
