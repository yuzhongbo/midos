package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.TraceLogger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MasterOrchestrator {

    private static final String MASTER_AGENT_NAME = "master-orchestrator";

    private final Map<String, Agent> agents;
    private final TraceLogger traceLogger;
    private final OrchestratorMemoryWriter memoryWriter;

    public MasterOrchestrator(PlannerAgent plannerAgent,
                              ExecutorAgent executorAgent,
                              MemoryAgent memoryAgent,
                              ToolAgent toolAgent,
                              TraceLogger traceLogger) {
        this(plannerAgent, executorAgent, memoryAgent, toolAgent, traceLogger, null);
    }

    public MasterOrchestrator(PlannerAgent plannerAgent,
                              ExecutorAgent executorAgent,
                              MemoryAgent memoryAgent,
                              ToolAgent toolAgent,
                              TraceLogger traceLogger,
                              OrchestratorMemoryWriter memoryWriter) {
        this.agents = Map.of(
                plannerAgent.name(), plannerAgent,
                executorAgent.name(), executorAgent,
                memoryAgent.name(), memoryAgent,
                toolAgent.name(), toolAgent
        );
        this.traceLogger = traceLogger;
        this.memoryWriter = memoryWriter;
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

        gateway.send(buildMemoryReadMessage(state));
        AgentResponse planResponse = gateway.send(buildPlanMessage(state));
        TaskGraph plannedGraph = resolvePlannedGraph(planResponse, effectiveDecision);
        String graphId = state.traceId.isBlank() ? UUID.randomUUID().toString() : state.traceId + "-graph";
        AgentResponse executionResponse = gateway.send(buildExecutionMessage(state, plannedGraph, planResponse, graphId));
        SkillResult finalResult = resolveFinalResult(executionResponse);
        if (!shouldSkipMemoryWrite(safeRequest)) {
            AgentResponse memoryResponse = gateway.send(buildMemoryWriteMessage(state, finalResult, plannedGraph, executionResponse, graphId));
            commitMemoryWrites(safeRequest.userId(), memoryResponse);
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
        return AgentMessage.of(
                MASTER_AGENT_NAME,
                "memory-agent",
                AgentTask.of(AgentTaskType.MEMORY_READ, state.request.userId(), state.request.userInput(), payload)
        );
    }

    private AgentMessage buildPlanMessage(SessionState state) {
        Map<String, Object> payload = Map.of(
                "stage", "planning"
        );
        return AgentMessage.of(
                MASTER_AGENT_NAME,
                "planner-agent",
                AgentTask.of(AgentTaskType.PLAN_REQUEST, state.request.userId(), state.request.userInput(), payload)
        );
    }

    private AgentMessage buildExecutionMessage(SessionState state,
                                               TaskGraph plannedGraph,
                                               AgentResponse planResponse,
                                               String graphId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graph", plannedGraph);
        payload.put("graphId", graphId);
        payload.put("decision", state.decision);
        payload.put("rationale", safeText(planResponse == null ? "" : planResponse.summary(), "plan ready"));
        payload.put("fallbackGraph", planResponse != null && Boolean.TRUE.equals(planResponse.contextPatch().get("multiAgent.plan.fallbackGraph")));
        return AgentMessage.of(
                MASTER_AGENT_NAME,
                "executor-agent",
                AgentTask.of(AgentTaskType.EXECUTE_GRAPH, state.request.userId(), state.request.userInput(), payload)
        );
    }

    private AgentMessage buildMemoryWriteMessage(SessionState state,
                                                 SkillResult finalResult,
                                                 TaskGraph plannedGraph,
                                                 AgentResponse executionResponse,
                                                 String graphId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        TaskGraphExecutionResult graphResult = executionResponse == null
                ? null
                : objectValue(executionResponse.contextPatch().get("multiAgent.graphResult"), TaskGraphExecutionResult.class);
        payload.put("kind", finalResult != null && finalResult.success() ? "procedure" : "skill-usage");
        payload.put("graph", plannedGraph);
        payload.put("contextAttributes", graphResult == null ? Map.of() : graphResult.contextAttributes());
        payload.put("intent", state.decision == null ? "" : state.decision.intent());
        payload.put("trigger", state.request.userInput());
        payload.put("userInput", state.request.userInput());
        payload.put("result", finalResult);
        payload.put("graphId", graphId);
        if (graphResult != null) {
            payload.put("graphResult", graphResult);
        }
        return AgentMessage.of(
                MASTER_AGENT_NAME,
                "memory-agent",
                AgentTask.of(AgentTaskType.MEMORY_WRITE, state.request.userId(), state.request.userInput(), payload)
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

    private boolean shouldSkipMemoryWrite(DecisionOrchestrator.OrchestrationRequest request) {
        Map<String, Object> profileContext = request == null ? Map.of() : request.safeProfileContext();
        Object value = profileContext.get("multiAgent.skipMemoryWrite");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private void commitMemoryWrites(String userId, AgentResponse response) {
        if (memoryWriter == null || response == null || response.contextPatch() == null) {
            return;
        }
        MemoryWriteBatch batch = objectValue(response.contextPatch().get(DefaultMemoryAgent.WRITE_BATCH_KEY), MemoryWriteBatch.class);
        if (batch != null) {
            memoryWriter.commit(userId, batch);
        }
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

    private TaskGraph resolvePlannedGraph(AgentResponse planResponse, Decision decision) {
        TaskGraph graph = planResponse == null
                ? null
                : objectValue(planResponse.contextPatch().get("multiAgent.plan.graph"), TaskGraph.class);
        if (graph != null && !graph.isEmpty()) {
            return graph;
        }
        String target = decision == null ? "" : firstNonBlank(decision.target(), decision.intent());
        if (target.isBlank()) {
            return new TaskGraph(List.of(), List.of());
        }
        return TaskGraph.linear(List.of(target), decision == null || decision.params() == null ? Map.of() : decision.params());
    }

    private SkillResult resolveFinalResult(AgentResponse executionResponse) {
        if (executionResponse != null && executionResponse.result() != null) {
            return executionResponse.result();
        }
        return SkillResult.failure(MASTER_AGENT_NAME, "no final result");
    }

    private <T> T objectValue(Object value, Class<T> type) {
        if (type == null || value == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
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
                            "type", message.type() == null ? "unknown" : message.type().name(),
                            "taskId", message.taskId(),
                            "payloadType", message.payload() == null ? "unknown" : message.payload().getClass().getSimpleName()
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
            AgentContext agentContext = new AgentContext(
                    state.traceId,
                    state.decision,
                    state.request,
                    state.sharedStateSnapshot(),
                    new SessionGateway(state)
            );
            response = dispatch(agent, message, agentContext);
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

    private AgentResponse dispatch(Agent agent, AgentMessage message, AgentContext context) {
        AgentTaskType taskType = message == null ? null : message.type();
        if (taskType == null) {
            return agent.observe(message, context);
        }
        return switch (taskType) {
            case PLAN_REQUEST -> agent.plan(message, context);
            case EXECUTE_GRAPH, TOOL_CALL -> agent.execute(message, context);
            case MEMORY_READ, MEMORY_WRITE -> agent.observe(message, context);
        };
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
                    message == null ? "unknown" : message.to() + ":" + (message.type() == null ? "unknown" : message.type().name().toLowerCase()),
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
