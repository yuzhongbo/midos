package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.TraceLogger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

@Service
public class MasterOrchestrator {

    private static final String MASTER_AGENT_NAME = "master-orchestrator";

    private final Map<String, Agent> agents;
    private final TraceLogger traceLogger;

    public MasterOrchestrator(PlannerAgent plannerAgent,
                              ExecutorAgent executorAgent,
                              MemoryAgent memoryAgent,
                              ToolAgent toolAgent,
                              TraceLogger traceLogger) {
        this.agents = Map.of(
                plannerAgent.name(), plannerAgent,
                executorAgent.name(), executorAgent,
                memoryAgent.name(), memoryAgent,
                toolAgent.name(), toolAgent
        );
        this.traceLogger = traceLogger;
    }

    public MasterOrchestrationResult execute(String userId,
                                             String userInput,
                                             Decision decision,
                                             Map<String, Object> profileContext) {
        DecisionOrchestrator.OrchestrationRequest request = new DecisionOrchestrator.OrchestrationRequest(
                userId,
                userInput,
                new com.zhongbo.mindos.assistant.common.SkillContext(userId, userInput, profileContext),
                profileContext
        );
        return orchestrate(decision, request);
    }

    public MasterOrchestrationResult orchestrate(Decision decision,
                                                 DecisionOrchestrator.OrchestrationRequest request) {
        Decision effectiveDecision = normalizeDecision(decision);
        if (effectiveDecision == null) {
            SkillResult failure = SkillResult.failure(MASTER_AGENT_NAME, "missing decision");
            return new MasterOrchestrationResult(
                    failure,
                    new ExecutionTraceDto("multi-agent-master", 0, new CritiqueReportDto(false, "missing decision", "failed"), List.of()),
                    List.of(),
                    Map.of()
            );
        }
        DecisionOrchestrator.OrchestrationRequest safeRequest = request == null
                ? new DecisionOrchestrator.OrchestrationRequest("", "", new com.zhongbo.mindos.assistant.common.SkillContext("", "", Map.of()), Map.of())
                : request;

        String traceId = traceLogger == null
                ? UUID.randomUUID().toString()
                : traceLogger.start(safeRequest.userId(), effectiveDecision.intent(), safeRequest.userInput());
        SessionState state = new SessionState(traceId, effectiveDecision, safeRequest, initialState(effectiveDecision, safeRequest));
        SessionGateway gateway = new SessionGateway(state);

        java.util.Deque<AgentMessage> queue = new ArrayDeque<>();
        queue.add(buildMemoryReadMessage(state));
        queue.add(buildPlanMessage(state));

        while (!queue.isEmpty()) {
            AgentMessage message = queue.removeFirst();
            AgentResponse response = gateway.send(message);
            if (response != null && !response.outboundMessages().isEmpty()) {
                queue.addAll(response.outboundMessages());
            }
        }

        SkillResult finalResult = state.finalResult();
        if (finalResult == null) {
            finalResult = SkillResult.failure(MASTER_AGENT_NAME, "no final result");
        }

        ExecutionTraceDto trace = new ExecutionTraceDto(
                "multi-agent-master",
                0,
                new CritiqueReportDto(
                        finalResult.success(),
                        finalResult.success() ? "multi-agent success" : safeText(finalResult.output(), "multi-agent failed"),
                        finalResult.success() ? "none" : "failed"
                ),
                state.stepsSnapshot()
        );

        if (traceLogger != null) {
            traceLogger.finish(
                    traceId,
                    finalResult.success(),
                    safeText(finalResult.output(), ""),
                    Map.of(
                            "userId", safeRequest.userId(),
                            "resultSkill", safeText(finalResult.skillName(), MASTER_AGENT_NAME),
                            "messages", state.transcriptSnapshot().size()
                    )
            );
        }

        return new MasterOrchestrationResult(
                finalResult,
                trace,
                state.transcriptSnapshot(),
                state.sharedStateSnapshot()
        );
    }

    private AgentMessage buildMemoryReadMessage(SessionState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("focusKey", state.decision.target());
        payload.put("effectiveParams", state.decision.params());
        payload.put("requestedKeys", inferKeys(state.decision));
        return AgentMessage.create(
                MASTER_AGENT_NAME,
                "memory-agent",
                state.traceId,
                AgentTask.of(AgentTaskType.MEMORY_READ, state.request.userId(), state.request.userInput(), payload),
                "",
                Map.of("stage", "memory-read")
        );
    }

    private AgentMessage buildPlanMessage(SessionState state) {
        Map<String, Object> payload = Map.of(
                "stage", "planning"
        );
        return AgentMessage.create(
                MASTER_AGENT_NAME,
                "planner-agent",
                state.traceId,
                AgentTask.of(AgentTaskType.PLAN_REQUEST, state.request.userId(), state.request.userInput(), payload),
                "",
                Map.of("stage", "planning")
        );
    }

    private String safeText(String value, String fallback) {
        if (value == null) {
            return fallback == null ? "" : fallback;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return normalized;
    }

    private List<String> inferKeys(Decision decision) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (decision != null && decision.params() != null) {
            keys.addAll(decision.params().keySet());
        }
        if (decision != null) {
            if (decision.target() != null && !decision.target().isBlank()) {
                keys.add(decision.target());
            }
            if (decision.intent() != null && !decision.intent().isBlank()) {
                keys.add(decision.intent());
            }
        }
        return List.copyOf(keys);
    }

    private Map<String, Object> initialState(Decision decision, DecisionOrchestrator.OrchestrationRequest request) {
        Map<String, Object> state = new LinkedHashMap<>();
        putIfNotNull(state, "userId", request.userId());
        putIfNotNull(state, "userInput", request.userInput());
        putIfNotNull(state, "decision", decision);
        putIfNotNull(state, "decision.intent", decision.intent());
        putIfNotNull(state, "decision.target", decision.target());
        putIfNotNull(state, "decision.confidence", decision.confidence());
        putIfNotNull(state, "decision.requireClarify", decision.requireClarify());
        if (request.skillContext() != null && request.skillContext().attributes() != null) {
            request.skillContext().attributes().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    state.put(key, value);
                }
            });
        }
        request.safeProfileContext().forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                state.put(key, value);
            }
        });
        return state;
    }

    private void putIfNotNull(Map<String, Object> state, String key, Object value) {
        if (state == null || key == null || key.isBlank() || value == null) {
            return;
        }
        state.put(key, value);
    }

    private Decision normalizeDecision(Decision decision) {
        if (decision == null) {
            return null;
        }
        String intent = firstNonBlank(decision.intent(), decision.target());
        String target = firstNonBlank(decision.target(), decision.intent());
        if (intent.isBlank() && target.isBlank()) {
            return null;
        }
        return new Decision(
                intent.isBlank() ? target : intent,
                target.isBlank() ? intent : target,
                decision.params(),
                decision.confidence(),
                decision.requireClarify()
        );
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return "";
    }

    private Agent agentFor(String name) {
        return agents.get(name);
    }

    private AgentResponse send(SessionState state, AgentMessage message) {
        Instant startedAt = Instant.now();
        Agent agent = agentFor(message.to());
        if (traceLogger != null) {
            traceLogger.event(
                    state.traceId,
                    "multi-agent",
                    "message",
                    Map.of(
                            "from", message.from(),
                            "to", message.to(),
                            "type", message.type().name(),
                            "messageId", message.messageId(),
                            "taskId", message.taskId()
                    )
            );
        }
        AgentResponse response;
        if (agent == null) {
            response = AgentResponse.completed(
                    MASTER_AGENT_NAME,
                    SkillResult.failure(message.to(), "unknown agent"),
                    false,
                    List.of(),
                    Map.of(),
                    "unknown agent"
            );
        } else {
            response = agent.handle(message, new AgentContext(
                    state.traceId,
                    state.decision,
                    state.request,
                    state.sharedStateSnapshot(),
                    new SessionGateway(state)
            ));
        }
        Instant finishedAt = Instant.now();
        state.record(message, response, startedAt, finishedAt);
        if (traceLogger != null) {
            traceLogger.event(
                    state.traceId,
                    "multi-agent",
                    "response",
                    Map.of(
                            "agent", message.to(),
                            "success", response.result() == null || response.result().success(),
                            "summary", response.summary(),
                            "usedFallback", response.usedFallback()
                    )
            );
        }
        return response;
    }

    private final class SessionGateway implements AgentGateway {
        private final SessionState state;

        private SessionGateway(SessionState state) {
            this.state = state;
        }

        @Override
        public AgentResponse send(AgentMessage message) {
            return MasterOrchestrator.this.send(state, message);
        }
    }

    private static final class SessionState {
        private final String traceId;
        private final Decision decision;
        private final DecisionOrchestrator.OrchestrationRequest request;
        private final Map<String, Object> sharedState = new LinkedHashMap<>();
        private final List<AgentMessage> transcript = new ArrayList<>();
        private final List<PlanStepDto> steps = new ArrayList<>();
        private SkillResult finalResult;

        private SessionState(String traceId,
                             Decision decision,
                             DecisionOrchestrator.OrchestrationRequest request,
                             Map<String, Object> initialState) {
            this.traceId = traceId == null ? "" : traceId.trim();
            this.decision = decision;
            this.request = request;
            if (initialState != null && !initialState.isEmpty()) {
                this.sharedState.putAll(initialState);
            }
        }

        private synchronized Map<String, Object> sharedStateSnapshot() {
            return sharedState.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(sharedState));
        }

        private synchronized List<AgentMessage> transcriptSnapshot() {
            return transcript.isEmpty() ? List.of() : List.copyOf(transcript);
        }

        private synchronized List<PlanStepDto> stepsSnapshot() {
            return steps.isEmpty() ? List.of() : List.copyOf(steps);
        }

        private synchronized SkillResult finalResult() {
            return finalResult;
        }

        private synchronized void record(AgentMessage message, AgentResponse response, Instant startedAt, Instant finishedAt) {
            if (message != null) {
                transcript.add(message);
            }
            if (response != null && response.contextPatch() != null && !response.contextPatch().isEmpty()) {
                sharedState.putAll(response.contextPatch());
            }
            if (response != null && response.result() != null && "executor-agent".equalsIgnoreCase(message.to())) {
                finalResult = response.result();
                sharedState.put("multiAgent.finalResult", response.result());
            }
            if (response != null && response.result() != null) {
                sharedState.put("multiAgent.lastResult", response.result());
            }
            if (response != null && !response.summary().isBlank()) {
                sharedState.put("multiAgent.lastSummary", response.summary());
            }
            if (response != null) {
                sharedState.put("multiAgent.lastUsedFallback", response.usedFallback());
            }
            String status = response == null
                    ? "failed"
                    : response.result() == null
                    ? "done"
                    : response.result().success() ? "success" : "failed";
            String note = response == null
                    ? "no response"
                    : !response.summary().isBlank()
                    ? response.summary()
                    : response.result() == null
                    ? "ack"
                    : safeText(response.result().output(), "ack");
            steps.add(new PlanStepDto(
                    message == null ? "unknown" : message.to() + ":" + message.type().name().toLowerCase(),
                    status,
                    message == null ? "unknown" : message.to(),
                    note,
                    startedAt,
                    finishedAt
            ));
        }

        private String safeText(String value, String fallback) {
            if (value == null) {
                return fallback == null ? "" : fallback;
            }
            String normalized = value.trim();
            if (normalized.isBlank()) {
                return fallback == null ? "" : fallback;
            }
            return normalized;
        }
    }
}
