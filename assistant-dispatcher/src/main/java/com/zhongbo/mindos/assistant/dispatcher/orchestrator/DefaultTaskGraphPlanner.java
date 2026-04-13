package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultTaskGraphPlanner implements TaskGraphPlanner {

    private static final double TASK_PLAN_LOW_CONFIDENCE_THRESHOLD = 0.70;

    private final CandidateChainBuilder candidateChainBuilder;
    private final SlowPathPlanBuilder slowPathPlanBuilder;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private SearchPlanner searchPlanner;
    private ProceduralMemory proceduralMemory;
    private DispatcherMemoryFacade proceduralMemoryFacade;

    @Autowired
    public DefaultTaskGraphPlanner(CandidatePlanner candidatePlanner,
                                   FallbackPlan fallbackPlan,
                                   DispatcherMemoryFacade dispatcherMemoryFacade,
                                   @Value("${mindos.dispatcher.orchestrator.max-loops:3}") int maxLoops) {
        this.candidateChainBuilder = new CandidateChainBuilder(candidatePlanner, fallbackPlan);
        this.slowPathPlanBuilder = new SlowPathPlanBuilder(
                this.candidateChainBuilder,
                () -> this.searchPlanner,
                this::isMcpSkill
        );
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
    }

    @Override
    public TaskGraphPlan plan(Decision decision, DecisionOrchestrator.OrchestrationRequest request) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return TaskGraphPlan.clarification(decision, "", "missing target");
        }
        if ("semantic.clarify".equalsIgnoreCase(decision.target()) || decision.requireClarify()) {
            return TaskGraphPlan.clarification(decision, decision.target(), "clarification requested");
        }
        Map<String, Object> effectiveParams = buildEffectiveParams(decision.params(), request == null ? null : request.skillContext());
        TaskGraph explicitGraph = TaskGraph.fromDsl(effectiveParams);
        if (!explicitGraph.isEmpty()) {
            return new TaskGraphPlan(
                    decision,
                    explicitGraph,
                    slowPathPlanBuilder.attachTaskGraph(effectiveParams, explicitGraph),
                    "slow-path",
                    resolveIntent(decision),
                    request == null ? "" : request.userInput(),
                    new TaskGraph(List.of(), List.of()),
                    Map.of(),
                    "",
                    "",
                    ""
            );
        }
        if (!isExplicitRequest(request)) {
            java.util.Optional<ProceduralMemory.ReusableProcedure> reusableProcedure = matchReusableProcedure(decision, request, effectiveParams);
            if (reusableProcedure.isPresent() && !reusableProcedure.get().taskGraph().isEmpty()) {
                TaskGraph graph = reusableProcedure.get().taskGraph();
                return new TaskGraphPlan(
                        decision,
                        graph,
                        slowPathPlanBuilder.attachTaskGraph(effectiveParams, graph),
                        "procedural-memory",
                        resolveIntent(decision),
                        request == null ? "" : request.userInput(),
                        new TaskGraph(List.of(), List.of()),
                        Map.of(),
                        "",
                        "",
                        ""
                );
            }
        }
        if ("task.plan".equalsIgnoreCase(decision.target()) || shouldUseSlowPath(decision)) {
            return slowPathPlan(decision, effectiveParams, request, null, "slow-path", true);
        }
        TaskGraph singleNodeGraph = buildSingleNodeGraph(decision, effectiveParams, request);
        if (singleNodeGraph.isEmpty()) {
            return TaskGraphPlan.clarification(decision, decision.target(), "缺少可执行候选");
        }
        return new TaskGraphPlan(
                decision,
                singleNodeGraph,
                slowPathPlanBuilder.attachTaskGraph(effectiveParams, singleNodeGraph),
                "single-task-graph",
                resolveIntent(decision),
                request == null ? "" : request.userInput(),
                new TaskGraph(List.of(), List.of()),
                Map.of(),
                "",
                "",
                ""
        );
    }

    @Autowired(required = false)
    void setSearchPlanner(SearchPlanner searchPlanner) {
        this.searchPlanner = searchPlanner;
    }

    @Autowired(required = false)
    void setProceduralMemory(ProceduralMemory proceduralMemory) {
        this.proceduralMemory = proceduralMemory;
        this.proceduralMemoryFacade = proceduralMemory == null
                ? null
                : new DispatcherMemoryFacade((com.zhongbo.mindos.assistant.memory.MemoryGateway) null, null, proceduralMemory);
    }

    private TaskGraphPlan slowPathPlan(Decision decision,
                                       Map<String, Object> effectiveParams,
                                       DecisionOrchestrator.OrchestrationRequest request,
                                       String excludeSkill,
                                       String strategy,
                                       boolean requireMultipleSteps) {
        TaskGraph taskGraph = slowPathPlanBuilder.buildTaskGraph(
                decision,
                effectiveParams,
                request,
                excludeSkill,
                requireMultipleSteps
        );
        if (taskGraph.isEmpty()) {
            return TaskGraphPlan.clarification(decision, decision.target(), "无法生成 task graph");
        }
        return new TaskGraphPlan(
                decision,
                taskGraph,
                slowPathPlanBuilder.attachTaskGraph(effectiveParams, taskGraph),
                strategy,
                resolveIntent(decision),
                request == null ? "" : request.userInput(),
                new TaskGraph(List.of(), List.of()),
                Map.of(),
                "",
                "",
                ""
        );
    }

    private TaskGraph buildSingleNodeGraph(Decision decision,
                                           Map<String, Object> effectiveParams,
                                           DecisionOrchestrator.OrchestrationRequest request) {
        String target = decision == null || decision.target() == null ? "" : decision.target().trim();
        if (target.isBlank()) {
            return new TaskGraph(List.of(), List.of());
        }
        TaskNode node = new TaskNode(
                "task-1",
                target,
                effectiveParams,
                List.of(),
                "result",
                false
        );
        return new TaskGraph(List.of(node), List.of());
    }

    private java.util.Optional<ProceduralMemory.ReusableProcedure> matchReusableProcedure(Decision decision,
                                                                                           DecisionOrchestrator.OrchestrationRequest request,
                                                                                           Map<String, Object> effectiveParams) {
        if (decision == null) {
            return java.util.Optional.empty();
        }
        return activeProcedureMemoryFacade().matchReusableProcedure(
                request == null ? "" : request.userId(),
                request == null ? "" : request.userInput(),
                resolveIntent(decision),
                effectiveParams
        );
    }

    private boolean isExplicitRequest(DecisionOrchestrator.OrchestrationRequest request) {
        if (request == null) {
            return false;
        }
        String userInput = request.userInput() == null ? "" : request.userInput().trim();
        if (userInput.startsWith("skill:")) {
            return true;
        }
        if (userInput.startsWith("{") && userInput.contains("\"skill\"")) {
            return true;
        }
        if (isDirectBuiltinCommand(userInput)) {
            return true;
        }
        SkillContext context = request.skillContext();
        if (context == null || context.attributes() == null) {
            return false;
        }
        return hasText(context.attributes().get("explicitTarget"))
                || hasText(context.attributes().get("explicitSkill"))
                || hasText(context.attributes().get("_target"));
    }

    private boolean isDirectBuiltinCommand(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("time") || normalized.startsWith("time ")) {
            return true;
        }
        return normalized.equals("echo") || normalized.startsWith("echo ");
    }

    private DispatcherMemoryFacade activeProcedureMemoryFacade() {
        return proceduralMemoryFacade == null ? dispatcherMemoryFacade : proceduralMemoryFacade;
    }

    private boolean shouldUseSlowPath(Decision decision) {
        return decision != null && decision.confidence() < TASK_PLAN_LOW_CONFIDENCE_THRESHOLD;
    }

    private boolean isMcpSkill(String target) {
        return target != null && target.startsWith("mcp.");
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isBlank();
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

    private String resolveIntent(Decision decision) {
        if (decision == null) {
            return "";
        }
        if (decision.intent() != null && !decision.intent().isBlank()) {
            return decision.intent();
        }
        return decision.target() == null ? "" : decision.target();
    }
}
