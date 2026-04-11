package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentRole;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.ExecutorAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MemoryAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.PlannerAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.ToolAgent;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AutonomousTestFixtures {

    private AutonomousTestFixtures() {
    }

    static final class MemoryGatewayStub implements MemoryGateway {
        private final List<ConversationTurn> history;
        private final List<SkillUsageStats> stats;
        final List<String> semanticTexts = new ArrayList<>();
        final List<String> semanticBuckets = new ArrayList<>();
        final List<ProceduralMemoryEntry> proceduralEntries = new ArrayList<>();
        LongTaskProgressCall lastLongTaskProgress;

        MemoryGatewayStub(List<ConversationTurn> history, List<SkillUsageStats> stats) {
            this.history = history == null ? List.of() : List.copyOf(history);
            this.stats = stats == null ? List.of() : List.copyOf(stats);
        }

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
            if (entry != null) {
                proceduralEntries.add(entry);
            }
        }

        @Override
        public void writeSemantic(String userId, SemanticMemoryEntry entry) {
            if (entry != null) {
                semanticTexts.add(entry.text());
                semanticBuckets.add("entry");
            }
        }

        @Override
        public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
            semanticTexts.add(text);
            semanticBuckets.add(bucket);
        }

        @Override
        public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
            return profile == null ? PreferenceProfile.empty() : profile;
        }

        @Override
        public LongTask createLongTask(String userId,
                                       String title,
                                       String objective,
                                       List<String> steps,
                                       Instant dueAt,
                                       Instant nextCheckAt) {
            return new LongTask(
                    "task-" + Math.abs((userId + title + objective).hashCode()),
                    userId,
                    title,
                    objective,
                    LongTaskStatus.PENDING,
                    0,
                    steps == null ? List.of() : List.copyOf(steps),
                    List.of(),
                    List.of(),
                    "",
                    Instant.now(),
                    Instant.now(),
                    dueAt,
                    nextCheckAt,
                    null,
                    null
            );
        }

        @Override
        public LongTask updateLongTaskProgress(String userId,
                                               String taskId,
                                               String workerId,
                                               String completedStep,
                                               String note,
                                               String blockedReason,
                                               Instant nextCheckAt,
                                               boolean markCompleted) {
            lastLongTaskProgress = new LongTaskProgressCall(
                    userId,
                    taskId,
                    workerId,
                    completedStep,
                    note,
                    blockedReason,
                    nextCheckAt,
                    markCompleted
            );
            return new LongTask(
                    taskId,
                    userId,
                    note == null || note.isBlank() ? "task" : note,
                    note == null || note.isBlank() ? "task" : note,
                    markCompleted ? LongTaskStatus.COMPLETED : LongTaskStatus.RUNNING,
                    markCompleted ? 100 : 50,
                    List.of(),
                    completedStep == null || completedStep.isBlank() ? List.of() : List.of(completedStep),
                    note == null || note.isBlank() ? List.of() : List.of(note),
                    blockedReason,
                    Instant.now(),
                    Instant.now(),
                    nextCheckAt,
                    nextCheckAt,
                    workerId,
                    null
            );
        }

        @Override
        public LongTask updateLongTaskStatus(String userId,
                                             String taskId,
                                             LongTaskStatus status,
                                             String note,
                                             Instant nextCheckAt) {
            return new LongTask(
                    taskId,
                    userId,
                    note == null || note.isBlank() ? "task" : note,
                    note == null || note.isBlank() ? "task" : note,
                    status,
                    status == LongTaskStatus.COMPLETED ? 100 : 50,
                    List.of(),
                    List.of(),
                    note == null || note.isBlank() ? List.of() : List.of(note),
                    "",
                    Instant.now(),
                    Instant.now(),
                    nextCheckAt,
                    nextCheckAt,
                    null,
                    null
            );
        }
    }

    static final class RecordingGraphMemoryGateway implements GraphMemoryGateway {
        final Map<String, MemoryNode> nodes = new LinkedHashMap<>();
        final Map<String, MemoryEdge> edges = new LinkedHashMap<>();
        final List<String> deletedNodeIds = new ArrayList<>();

        @Override
        public MemoryNode upsertNode(String userId, MemoryNode node) {
            if (node != null) {
                nodes.put(node.id(), node);
            }
            return node;
        }

        @Override
        public MemoryEdge upsertEdge(String userId, MemoryEdge edge) {
            if (edge != null) {
                edges.put(edge.from() + "->" + edge.to() + ":" + edge.relation(), edge);
            }
            return edge;
        }

        @Override
        public boolean deleteNode(String userId, String nodeId) {
            if (nodeId == null || nodeId.isBlank()) {
                return false;
            }
            deletedNodeIds.add(nodeId);
            nodes.remove(nodeId);
            edges.values().removeIf(edge -> nodeId.equals(edge.from()) || nodeId.equals(edge.to()));
            return true;
        }
    }

    static final class StubPlannerAgent implements PlannerAgent {
        @Override
        public String name() {
            return "planner-agent";
        }

        @Override
        public AgentRole role() {
            return AgentRole.PLANNER;
        }
    }

    static final class StubExecutorAgent implements ExecutorAgent {
        @Override
        public String name() {
            return "executor-agent";
        }

        @Override
        public AgentRole role() {
            return AgentRole.EXECUTOR;
        }
    }

    static final class StubMemoryAgent implements MemoryAgent {
        @Override
        public String name() {
            return "memory-agent";
        }

        @Override
        public AgentRole role() {
            return AgentRole.MEMORY;
        }
    }

    static final class StubToolAgent implements ToolAgent {
        @Override
        public String name() {
            return "tool-agent";
        }

        @Override
        public AgentRole role() {
            return AgentRole.TOOL;
        }
    }

    static class StubMasterOrchestrator extends MasterOrchestrator {
        private final MasterOrchestrationResult result;
        Decision lastDecision;
        Map<String, Object> lastProfileContext = Map.of();
        String lastUserId;
        String lastUserInput;

        StubMasterOrchestrator(MasterOrchestrationResult result) {
            super(new StubPlannerAgent(), new StubExecutorAgent(), new StubMemoryAgent(), new StubToolAgent(), null);
            this.result = result;
        }

        @Override
        public MasterOrchestrationResult execute(String userId,
                                                 String userInput,
                                                 Decision decision,
                                                 Map<String, Object> profileContext) {
            this.lastUserId = userId;
            this.lastUserInput = userInput;
            this.lastDecision = decision;
            this.lastProfileContext = profileContext == null ? Map.of() : new LinkedHashMap<>(profileContext);
            return result;
        }

        @Override
        public MasterOrchestrationResult orchestrate(Decision decision,
                                                     com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest request) {
            this.lastDecision = decision;
            this.lastUserId = request == null ? null : request.userId();
            this.lastUserInput = request == null ? null : request.userInput();
            this.lastProfileContext = request == null ? Map.of() : new LinkedHashMap<>(request.safeProfileContext());
            return result;
        }
    }

    record LongTaskProgressCall(String userId,
                                String taskId,
                                String workerId,
                                String completedStep,
                                String note,
                                String blockedReason,
                                Instant nextCheckAt,
                                boolean markCompleted) {
    }
}
