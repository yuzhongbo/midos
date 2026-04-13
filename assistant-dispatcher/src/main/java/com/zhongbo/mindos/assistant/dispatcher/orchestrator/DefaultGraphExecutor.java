package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DefaultGraphExecutor implements GraphExecutor {

    private final DecisionExecutor decisionExecutor;
    private final GraphExecutionPlanStore planStore;

    @Autowired
    public DefaultGraphExecutor(DecisionExecutor decisionExecutor,
                                GraphExecutionPlanStore planStore) {
        this.decisionExecutor = decisionExecutor;
        this.planStore = planStore;
    }

    @Override
    public OrchestrationExecutionResult execute(TaskGraph graph) {
        GraphExecutionPlanStore.StoredGraphPlan stored = planStore.consume(graph);
        TaskGraphPlan plan = stored == null ? null : stored.plan();
        DecisionOrchestrator.OrchestrationRequest request = stored == null
                ? new DecisionOrchestrator.OrchestrationRequest("", "", null, java.util.Map.of())
                : stored.request();
        return plan == null
                ? new OrchestrationExecutionResult(
                        null,
                        request,
                        graph,
                        new DecisionOrchestrator.OrchestrationOutcome(
                                SkillResult.failure("decision.orchestrator", "missing task graph plan"),
                                null,
                                null,
                                null,
                                "",
                                false
                        ),
                        null,
                        MemoryWriteBatch.empty()
                )
                : decisionExecutor.execute(plan, request);
    }

    void setProceduralMemory(com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory proceduralMemory) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setProceduralMemory(proceduralMemory);
        }
    }

    void setPlannerLearningStore(com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore plannerLearningStore) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setPlannerLearningStore(plannerLearningStore);
        }
    }

    void setPolicyUpdater(com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PolicyUpdater policyUpdater) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setPolicyUpdater(policyUpdater);
        }
    }

    void setAgentRouter(com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.AgentRouter agentRouter) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setAgentRouter(agentRouter);
        }
    }

    void setRecoveryManager(com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager recoveryManager) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setRecoveryManager(recoveryManager);
        }
    }

    void setTraceLogger(com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.TraceLogger traceLogger) {
        DefaultDecisionExecutor executor = defaultExecutor();
        if (executor != null) {
            executor.setTraceLogger(traceLogger);
        }
    }

    private DefaultDecisionExecutor defaultExecutor() {
        return decisionExecutor instanceof DefaultDecisionExecutor executor ? executor : null;
    }
}
