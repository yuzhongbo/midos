package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.InMemoryProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeamSearchCandidatePlannerTest {

    @Test
    void shouldUseKeywordGraphAndProceduralSignals() {
        SkillCatalogFacade skillEngine = skillEngine(Map.of(
                "student.get", 930,
                "student.analyze", 860,
                "teaching.plan", 820
        ));
        GraphMemory graphMemory = new GraphMemory();
        graphMemory.addNode("u1", new MemoryNode("n1", "skill", Map.of("name", "student.analyze", "skillName", "student.analyze"), null, null));

        BeamSearchCandidatePlanner planner = new BeamSearchCandidatePlanner(
                skillEngine,
                new DispatcherMemoryFacade(new MemoryGateway() {
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
                }, graphMemory, null),
                new InMemoryProcedureMemoryEngine()
        );

        List<SearchCandidate> candidates = planner.search(new SearchPlanningRequest("u1", "请先获取学生并分析再生成教学计划", "student.plan", Map.of(), 3, 3));

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.get(0).path().size() >= 2);
        assertTrue(candidates.get(0).metadata().containsKey("pathCost"));
        assertTrue(candidates.get(0).reasons().stream().anyMatch(reason -> reason.contains("keyword") || reason.contains("memory") || reason.contains("success")));
    }

    private SkillCatalogFacade skillEngine(Map<String, Integer> scores) {
        return new SkillCatalogFacade() {
            @Override
            public Optional<String> detectSkillName(String input) {
                return detectSkillCandidates(input, 1).stream().findFirst().map(SkillCandidate::skillName);
            }

            @Override
            public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
                return scores.entrySet().stream()
                        .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                        .limit(Math.max(0, limit))
                        .map(entry -> new SkillCandidate(entry.getKey(), entry.getValue()))
                        .toList();
            }

            @Override
            public Optional<SkillDescriptor> describeSkill(String skillName) {
                return Optional.of(new SkillDescriptor(skillName, skillName, List.of(skillName)));
            }

            @Override
            public List<SkillDescriptor> listSkillDescriptors() {
                return scores.keySet().stream()
                        .sorted()
                        .map(name -> new SkillDescriptor(name, name, List.of(name)))
                        .toList();
            }

            @Override
            public String describeAvailableSkills() {
                return String.join(", ", scores.keySet());
            }

            @Override
            public List<String> listAvailableSkillSummaries() {
                return scores.keySet().stream().sorted().toList();
            }
        };
    }
}
