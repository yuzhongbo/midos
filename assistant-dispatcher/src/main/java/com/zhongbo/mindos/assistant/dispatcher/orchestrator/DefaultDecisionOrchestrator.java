package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.AgentRouter;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PolicyUpdater;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RewardModel;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.TraceLogger;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationOutcome;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultDecisionOrchestrator implements DecisionOrchestrator {

    private final DecisionPlanner decisionPlanner;
    private final TaskGraphPlanner taskGraphPlanner;
    private final DecisionExecutor decisionExecutor;
    private final PostExecutionMemoryRecorder memoryRecorder;
    private final OrchestratorMemoryWriter memoryWriter;
    private final FailureNormalizer failureNormalizer;

    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop,
                                       FallbackPlan fallbackPlan,
                                       SkillExecutionGateway skillExecutionGateway,
                                       MemoryGateway memoryGateway,
                                       PostExecutionMemoryRecorder memoryRecorder,
                                       TaskExecutor taskExecutor,
                                       @Value("${mindos.dispatcher.parallel-routing.enabled:false}") boolean mcpParallelEnabled,
                                       @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long mcpPerSkillTimeoutMs,
                                       @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-ms:12000}") long eqCoachImTimeoutMs,
                                       @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-reply:我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。}") String eqCoachImTimeoutReply,
                                       @Value("${mindos.dispatcher.orchestrator.max-loops:3}") int maxLoops) {
        this(
                new DefaultDecisionPlanner(),
                new DefaultTaskGraphPlanner(
                        candidatePlanner,
                        fallbackPlan,
                        new DispatcherMemoryFacade(memoryGateway, null, null),
                        maxLoops
                ),
                new DefaultDecisionExecutor(
                        candidatePlanner,
                        paramValidator,
                        conversationLoop,
                        fallbackPlan,
                        skillExecutionGateway,
                        new DispatcherMemoryFacade(memoryGateway, null, null),
                        mcpParallelEnabled,
                        mcpPerSkillTimeoutMs,
                        eqCoachImTimeoutMs,
                        eqCoachImTimeoutReply,
                        maxLoops
                ),
                memoryRecorder,
                new OrchestratorMemoryWriter(
                        new DispatcherMemoryCommandService(new DispatcherMemoryFacade(memoryGateway, null, null), null)
                )
        );
    }

    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop,
                                       FallbackPlan fallbackPlan,
                                       SkillExecutionGateway skillExecutionGateway,
                                       DispatcherMemoryFacade dispatcherMemoryFacade,
                                       PostExecutionMemoryRecorder memoryRecorder,
                                       TaskExecutor taskExecutor,
                                       @Value("${mindos.dispatcher.parallel-routing.enabled:false}") boolean mcpParallelEnabled,
                                       @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long mcpPerSkillTimeoutMs,
                                       @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-ms:12000}") long eqCoachImTimeoutMs,
                                       @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-reply:我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。}") String eqCoachImTimeoutReply,
                                       @Value("${mindos.dispatcher.orchestrator.max-loops:3}") int maxLoops) {
        this(
                new DefaultDecisionPlanner(),
                new DefaultTaskGraphPlanner(
                        candidatePlanner,
                        fallbackPlan,
                        dispatcherMemoryFacade,
                        maxLoops
                ),
                new DefaultDecisionExecutor(
                        candidatePlanner,
                        paramValidator,
                        conversationLoop,
                        fallbackPlan,
                        skillExecutionGateway,
                        dispatcherMemoryFacade,
                        mcpParallelEnabled,
                        mcpPerSkillTimeoutMs,
                        eqCoachImTimeoutMs,
                        eqCoachImTimeoutReply,
                        maxLoops
                ),
                memoryRecorder,
                new OrchestratorMemoryWriter(new DispatcherMemoryCommandService(dispatcherMemoryFacade, null))
        );
    }

    @Autowired
    public DefaultDecisionOrchestrator(DecisionPlanner decisionPlanner,
                                       TaskGraphPlanner taskGraphPlanner,
                                       DecisionExecutor decisionExecutor,
                                       PostExecutionMemoryRecorder memoryRecorder,
                                       OrchestratorMemoryWriter memoryWriter) {
        this.decisionPlanner = decisionPlanner;
        this.taskGraphPlanner = taskGraphPlanner;
        this.decisionExecutor = decisionExecutor;
        this.memoryRecorder = memoryRecorder;
        this.memoryWriter = memoryWriter;
        this.failureNormalizer = new FailureNormalizer();
    }

    @Override
    public SkillResult execute(String userInput, String intent, Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        SkillContext context = new SkillContext("", userInput == null ? "" : userInput, safeParams);
        Decision decision = decisionPlanner.plan(userInput, intent, safeParams, context);
        OrchestrationRequest request = new OrchestrationRequest("", userInput == null ? "" : userInput, context, Map.of());
        TaskGraphPlan taskGraphPlan = taskGraphPlanner.plan(decision, request);
        OrchestrationOutcome outcome = decisionExecutor.execute(taskGraphPlan, request);
        if (outcome.hasResult() && outcome.result() != null && outcome.result().success()) {
            return outcome.result();
        }
        if (outcome.hasClarification()) {
            return outcome.clarification();
        }
        if (outcome.hasResult()) {
            List<String> attemptedCandidates = outcome.trace() == null || outcome.trace().steps() == null
                    ? List.of()
                    : outcome.trace().steps().stream()
                    .map(PlanStepDto::channel)
                    .filter(channel -> channel != null && !channel.isBlank())
                    .distinct()
                    .toList();
            return failureNormalizer.unifiedFailureResult(userInput, intent, attemptedCandidates, outcome.result());
        }
        return failureNormalizer.unifiedFailureResult(
                userInput,
                intent,
                List.of(),
                SkillResult.failure("decision.orchestrator", "all candidates failed")
        );
    }

    @Override
    public OrchestrationOutcome planAndExecute(OrchestrationRequest request, String intent, Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        SkillContext context = request == null || request.skillContext() == null
                ? new SkillContext(request == null ? "" : request.userId(), request == null ? "" : request.userInput(), safeParams)
                : request.skillContext();
        Decision decision = decisionPlanner.plan(
                request == null ? "" : request.userInput(),
                intent,
                safeParams,
                context
        );
        OrchestrationRequest effectiveRequest = request == null
                ? new OrchestrationRequest("", context.input(), context, Map.of())
                : new OrchestrationRequest(request.userId(), request.userInput(), context, request.safeProfileContext());
        return decisionExecutor.execute(taskGraphPlanner.plan(decision, effectiveRequest), effectiveRequest);
    }

    @Override
    public OrchestrationOutcome orchestrate(Decision decision, OrchestrationRequest request) {
        return decisionExecutor.execute(taskGraphPlanner.plan(decision, request), request);
    }

    @Override
    public void recordOutcome(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
        commitMemoryWrites(userId, memoryRecorder.record(userId, userInput, result, trace));
    }

    @Override
    public void commitMemoryWrites(String userId, MemoryWriteBatch batch) {
        if (memoryWriter != null) {
            memoryWriter.commit(userId, batch);
        }
    }

    void setSearchPlanner(SearchPlanner searchPlanner) {
        DefaultTaskGraphPlanner planner = defaultTaskGraphPlanner();
        if (planner != null) {
            planner.setSearchPlanner(searchPlanner);
        }
    }

    void setProceduralMemory(ProceduralMemory proceduralMemory) {
        DefaultTaskGraphPlanner planner = defaultTaskGraphPlanner();
        if (planner != null) {
            planner.setProceduralMemory(proceduralMemory);
        }
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setProceduralMemory(proceduralMemory);
        }
    }

    void setPlannerLearningStore(PlannerLearningStore plannerLearningStore) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setPlannerLearningStore(plannerLearningStore);
        }
    }

    void setPolicyUpdater(PolicyUpdater policyUpdater) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setPolicyUpdater(policyUpdater);
        }
    }

    void setAgentRouter(AgentRouter agentRouter) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setAgentRouter(agentRouter);
        }
    }

    void setRecoveryManager(RecoveryManager recoveryManager) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setRecoveryManager(recoveryManager);
        }
    }

    void setTraceLogger(TraceLogger traceLogger) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setTraceLogger(traceLogger);
        }
    }

    private DefaultDecisionExecutor defaultExecutor() {
        return decisionExecutor instanceof DefaultDecisionExecutor executor ? executor : null;
    }

    private DefaultTaskGraphPlanner defaultTaskGraphPlanner() {
        return taskGraphPlanner instanceof DefaultTaskGraphPlanner planner ? planner : null;
    }
}

@Component
final class DefaultDecisionExecutor implements DecisionExecutor {
    private static final double TASK_PLAN_LOW_CONFIDENCE_THRESHOLD = 0.70;

    private final ConversationLoop conversationLoop;
    private final TaskGraphCoordinator taskGraphCoordinator;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private ProceduralMemory proceduralMemory;
    private DispatcherMemoryFacade proceduralMemoryFacade;
    private PlannerLearningStore plannerLearningStore;
    private PolicyUpdater policyUpdater;
    private AgentRouter agentRouter;
    private RecoveryManager recoveryManager;
    private TraceLogger traceLogger;

    @Autowired
    DefaultDecisionExecutor(CandidatePlanner candidatePlanner,
                            ParamValidator paramValidator,
                            ConversationLoop conversationLoop,
                            FallbackPlan fallbackPlan,
                            SkillExecutionGateway skillExecutionGateway,
                            DispatcherMemoryFacade dispatcherMemoryFacade,
                            @Value("${mindos.dispatcher.parallel-routing.enabled:false}") boolean mcpParallelEnabled,
                            @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long mcpPerSkillTimeoutMs,
                            @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-ms:12000}") long eqCoachImTimeoutMs,
                            @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-reply:我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。}") String eqCoachImTimeoutReply,
                            @Value("${mindos.dispatcher.orchestrator.max-loops:3}") int maxLoops) {
        this.conversationLoop = conversationLoop;
        long effectiveMcpPerSkillTimeoutMs = Math.max(250, mcpPerSkillTimeoutMs);
        long effectiveEqCoachImTimeoutMs = Math.max(0L, eqCoachImTimeoutMs);
        String effectiveEqCoachImTimeoutReply = eqCoachImTimeoutReply == null || eqCoachImTimeoutReply.isBlank()
                ? "我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。"
                : eqCoachImTimeoutReply;
        this.taskGraphCoordinator = new TaskGraphCoordinator(
                this::activeProcedureMemoryFacade,
                () -> this.recoveryManager,
                () -> this.agentRouter,
                () -> this.plannerLearningStore,
                () -> this.policyUpdater,
                paramValidator,
                skillExecutionGateway,
                new TaskGraphCoordinatorBridgeAdapter(
                        this::clarificationOutcome,
                        this::buildEffectiveParams,
                        this::applyContextPatch,
                        this::traceEvent
                ),
                TASK_PLAN_LOW_CONFIDENCE_THRESHOLD,
                effectiveMcpPerSkillTimeoutMs,
                effectiveEqCoachImTimeoutMs,
                effectiveEqCoachImTimeoutReply
        );
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
    }

    @Autowired(required = false)
    void setProceduralMemory(ProceduralMemory proceduralMemory) {
        this.proceduralMemory = proceduralMemory;
        this.proceduralMemoryFacade = proceduralMemory == null
                ? null
                : new DispatcherMemoryFacade((com.zhongbo.mindos.assistant.memory.MemoryGateway) null, null, proceduralMemory);
    }

    @Autowired(required = false)
    void setPlannerLearningStore(PlannerLearningStore plannerLearningStore) {
        this.plannerLearningStore = plannerLearningStore;
    }

    @Autowired(required = false)
    void setPolicyUpdater(PolicyUpdater policyUpdater) {
        this.policyUpdater = policyUpdater;
    }

    @Autowired(required = false)
    void setAgentRouter(AgentRouter agentRouter) {
        this.agentRouter = agentRouter;
    }

    @Autowired(required = false)
    void setRecoveryManager(RecoveryManager recoveryManager) {
        this.recoveryManager = recoveryManager;
    }

    @Autowired(required = false)
    void setTraceLogger(TraceLogger traceLogger) {
        this.traceLogger = traceLogger;
    }

    @Override
    public OrchestrationOutcome execute(TaskGraphPlan plan, OrchestrationRequest request) {
        Decision decision = plan == null ? null : plan.decision();
        String traceId = startTrace(decision, request);
        OrchestrationOutcome outcome = null;
        try {
            if (plan == null) {
                outcome = clarificationOutcome("", "missing task graph plan");
            } else if (plan.requiresClarification()) {
                outcome = clarificationOutcome(plan.clarificationTarget(), plan.clarificationMessage());
            } else if (!plan.hasTaskGraph()) {
                outcome = clarificationOutcome(
                        plan.decision() == null ? "" : plan.decision().target(),
                        "missing task graph"
                );
            } else {
                traceEvent(traceId, "planner", "task-graph", Map.of(
                        "strategy", plan.strategy(),
                        "nodeCount", plan.taskGraph().nodes().size()
                ));
                outcome = executePlannedGraph(plan, request, traceId);
                if (shouldEscalateToFallback(plan, outcome)) {
                    traceEvent(traceId, "planner", "fallback-task-graph", Map.of(
                            "strategy", plan.fallbackStrategy(),
                            "nodeCount", plan.fallbackTaskGraph().nodes().size()
                    ));
                    outcome = taskGraphCoordinator.orchestrateTaskGraph(
                            decision,
                            plan.fallbackTaskGraph(),
                            plan.fallbackEffectiveParams(),
                            request,
                            traceId,
                            plan.fallbackStrategy(),
                            plan.intent(),
                            plan.trigger()
                    );
                }
            }
        } finally {
            finishTrace(traceId, decision, request, outcome);
        }
        return outcome;
    }

    private OrchestrationOutcome executePlannedGraph(TaskGraphPlan plan,
                                                     OrchestrationRequest request,
                                                     String traceId) {
        if (plan == null) {
            return clarificationOutcome("", "missing task graph plan");
        }
        return taskGraphCoordinator.orchestrateTaskGraph(
                plan.decision(),
                plan.taskGraph(),
                plan.effectiveParams(),
                request,
                traceId,
                plan.strategy(),
                plan.intent(),
                plan.trigger()
        );
    }

    private OrchestrationOutcome clarificationOutcome(String target, String message) {
        return new OrchestrationOutcome(
                null,
                null,
                conversationLoop.requestClarification(target, message),
                null,
                null,
                false
        );
    }

    private Map<String, Object> buildEffectiveParams(Map<String, Object> params, SkillContext skillContext) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (skillContext != null && skillContext.attributes() != null) {
            merged.putAll(skillContext.attributes());
        }
        if (params != null && !params.isEmpty()) {
            merged.putAll(params);
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private boolean shouldEscalateToFallback(TaskGraphPlan plan, OrchestrationOutcome outcome) {
        return plan != null
                && plan.hasFallbackTaskGraph()
                && outcome != null
                && outcome.hasResult()
                && outcome.result() != null
                && !outcome.result().success();
    }

    private DispatcherMemoryFacade activeProcedureMemoryFacade() {
        return proceduralMemoryFacade == null ? dispatcherMemoryFacade : proceduralMemoryFacade;
    }

    private Map<String, Object> applyContextPatch(Map<String, Object> baseContext, Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseContext != null && !baseContext.isEmpty()) {
            merged.putAll(baseContext);
        }
        if (patch != null && !patch.isEmpty()) {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private String startTrace(Decision decision, OrchestrationRequest request) {
        String userId = request == null ? "" : request.userId();
        String intent = decision == null ? "" : decision.intent();
        String input = request == null ? "" : request.userInput();
        if (traceLogger == null) {
            return java.util.UUID.randomUUID().toString();
        }
        return traceLogger.start(userId, intent, input);
    }

    private void traceEvent(String traceId, String phase, String action, Map<String, Object> details) {
        if (traceLogger == null || traceId == null || traceId.isBlank()) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        if (details != null) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                safe.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        traceLogger.event(traceId, phase, action, safe);
    }

    private void finishTrace(String traceId,
                             Decision decision,
                             OrchestrationRequest request,
                             OrchestrationOutcome outcome) {
        if (traceLogger == null || traceId == null || traceId.isBlank()) {
            return;
        }
        boolean success = outcome != null && outcome.hasResult() && outcome.result() != null && outcome.result().success();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("intent", decision == null ? "" : decision.intent());
        details.put("target", decision == null ? "" : decision.target());
        details.put("userId", request == null ? "" : request.userId());
        details.put("selectedSkill", outcome == null ? "" : outcome.selectedSkill());
        details.put("usedFallback", outcome != null && outcome.usedFallback());
        details.put("strategy", outcome != null && outcome.trace() != null ? outcome.trace().strategy() : "");
        traceLogger.finish(traceId, success, success ? "success" : "failure", details);
    }

}
