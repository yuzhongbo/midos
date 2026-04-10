package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutor;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;

import java.util.List;
import java.util.Map;

public class DualProcessCoordinator {

    private final System1Gate system1Gate;
    private final System2Planner system2Planner;
    private final TaskGraphExecutor taskGraphExecutor;
    private final ProcedureMemoryEngine procedureMemoryEngine;
    private final DecisionOrchestrator decisionOrchestrator;

    public DualProcessCoordinator(System1Gate system1Gate,
                                  System2Planner system2Planner,
                                  TaskGraphExecutor taskGraphExecutor,
                                  ProcedureMemoryEngine procedureMemoryEngine,
                                  DecisionOrchestrator decisionOrchestrator) {
        this.system1Gate = system1Gate;
        this.system2Planner = system2Planner;
        this.taskGraphExecutor = taskGraphExecutor;
        this.procedureMemoryEngine = procedureMemoryEngine;
        this.decisionOrchestrator = decisionOrchestrator;
    }

    public AgentDispatchResult dispatch(AgentDispatchRequest request) {
        if (system1Gate != null && system1Gate.shouldUseFastPath(request)) {
            return new AgentDispatchResult(
                    AgentMode.SYSTEM1,
                    decisionOrchestrator.orchestrate(request.decision(), request.orchestrationRequest()),
                    null,
                    null,
                    "system1-fast-path"
            );
        }
        System2Planner.PlanResult planResult = system2Planner == null
                ? new System2Planner.PlanResult(new TaskGraph(List.of()), null, "system2-unavailable")
                : system2Planner.plan(request);
        TaskGraph plannedGraph = planResult.graph();
        if (plannedGraph == null || plannedGraph.isEmpty()) {
            return new AgentDispatchResult(
                    AgentMode.SYSTEM1,
                    decisionOrchestrator.orchestrate(request.decision(), request.orchestrationRequest()),
                    null,
                    null,
                    "fallback-to-system1"
            );
        }
        TaskGraphExecutionResult graphResult = taskGraphExecutor.execute(
                plannedGraph,
                request.orchestrationRequest().skillContext(),
                (node, nodeContext) -> {
                    Decision stepDecision = new Decision(
                            request.decision().intent(),
                            node.target(),
                            nodeContext.attributes(),
                            1.0,
                            false
                    );
                    DecisionOrchestrator.OrchestrationOutcome outcome = decisionOrchestrator.orchestrate(
                            stepDecision,
                            new DecisionOrchestrator.OrchestrationRequest(
                                    request.orchestrationRequest().userId(),
                                    request.orchestrationRequest().userInput(),
                                    nodeContext,
                                    request.orchestrationRequest().safeProfileContext()
                            )
                    );
                    SkillResult result = outcome.hasResult() ? outcome.result() : outcome.clarification();
                    return new TaskGraphExecutor.NodeExecution(result, outcome.usedFallback());
                }
        );
        if (graphResult.success() && procedureMemoryEngine != null) {
            procedureMemoryEngine.recordSuccessfulGraph(
                    request.orchestrationRequest().userId(),
                    request.decision().intent(),
                    request.orchestrationRequest().userInput(),
                    plannedGraph,
                    graphResult.contextAttributes()
            );
        }
        SkillResult finalResult = graphResult.finalResult() == null
                ? SkillResult.failure("task.graph", "system2 produced no result")
                : graphResult.finalResult();
        DecisionOrchestrator.OrchestrationOutcome outcome = new DecisionOrchestrator.OrchestrationOutcome(
                finalResult,
                new SkillDsl(finalResult.skillName(), request.decision().params() == null ? Map.of() : request.decision().params()),
                null,
                null,
                finalResult.skillName(),
                graphResult.nodeResults().stream().anyMatch(TaskGraphExecutionResult.NodeResult::usedFallback)
        );
        return new AgentDispatchResult(AgentMode.SYSTEM2, outcome, plannedGraph, planResult.selectedCandidate(), planResult.rationale());
    }
}
