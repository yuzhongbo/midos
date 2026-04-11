package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPolicyUpdaterTest {

    @Test
    void shouldPersistPositiveRewardObservation() {
        InMemoryPlannerLearningStore learningStore = new InMemoryPlannerLearningStore();
        AtomicReference<ProceduralMemoryEntry> recorded = new AtomicReference<>();
        DefaultPolicyUpdater updater = new DefaultPolicyUpdater(learningStore, gateway(recorded), 1500L);

        RewardModel reward = updater.update("u1", "skill.policy", "local", true, 1200L, 48, false);

        assertEquals(1.0, reward.reward(), 1e-9);
        assertTrue(reward.reasons().contains("success:+1.0"));
        assertNotNull(recorded.get());
        assertEquals("skill.policy", recorded.get().skillName());
        assertTrue(recorded.get().input().contains("reward=1.0"));
        PlannerLearningStore.LearningSnapshot snapshot = learningStore.snapshot("u1", "skill.policy");
        assertTrue(snapshot.rewardScore() > 0.5);
        assertEquals("local", snapshot.preferredRoute());
    }

    @Test
    void shouldPenalizeFailureAndHighLatency() {
        InMemoryPlannerLearningStore learningStore = new InMemoryPlannerLearningStore();
        AtomicReference<ProceduralMemoryEntry> recorded = new AtomicReference<>();
        DefaultPolicyUpdater updater = new DefaultPolicyUpdater(learningStore, gateway(recorded), 1500L);

        RewardModel reward = updater.update("u1", "skill.policy", "remote", false, 2200L, 64, false);

        assertEquals(-1.5, reward.reward(), 1e-9);
        assertTrue(reward.reasons().contains("failure:-1.0"));
        assertTrue(reward.reasons().contains("high-latency:-0.5"));
        assertNotNull(recorded.get());
        assertTrue(recorded.get().input().contains("reward=-1.5"));
        PlannerLearningStore.LearningSnapshot snapshot = learningStore.snapshot("u1", "skill.policy");
        assertTrue(snapshot.rewardScore() < 0.5);
    }

    private MemoryGateway gateway(AtomicReference<ProceduralMemoryEntry> recorded) {
        return new MemoryGateway() {
            @Override
            public List<ConversationTurn> recentHistory(String userId) {
                return List.of();
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
            }

            @Override
            public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
                recorded.set(entry);
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
