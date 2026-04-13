package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.EvaluationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Evaluator;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.GoalExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.StrategyEvolutionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory;
import org.springframework.stereotype.Component;

@Component
public class EvaluationDepartmentService {

    private final Evaluator evaluator;
    private final KpiSystem kpiSystem;
    private final WorldMemory worldMemory;
    private final StrategyEvolutionEngine strategyEvolutionEngine;

    public EvaluationDepartmentService(Evaluator evaluator,
                                       KpiSystem kpiSystem,
                                       WorldMemory worldMemory,
                                       StrategyEvolutionEngine strategyEvolutionEngine) {
        this.evaluator = evaluator;
        this.kpiSystem = kpiSystem;
        this.worldMemory = worldMemory;
        this.strategyEvolutionEngine = strategyEvolutionEngine;
    }

    public OrganizationAssessment assess(Goal goal,
                                         AIOrganization organization,
                                         PlanningOutcome planningOutcome,
                                         GoalExecutionResult executionResult,
                                         OrgMemory orgMemory) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        if (planningOutcome == null || !planningOutcome.hasPlan()) {
            return OrganizationAssessment.empty(safeGoal, "planning department produced no task graph");
        }
        EvaluationResult evaluation = evaluator == null
                ? OrganizationAssessment.empty(safeGoal, "evaluator unavailable").evaluation()
                : evaluator.evaluate(executionResult, safeGoal);
        WorldMemory.ExecutionTrace worldTrace = worldMemory == null
                ? null
                : worldMemory.record(safeGoal, planningOutcome.selection(), executionResult, evaluation);
        if (worldTrace != null && strategyEvolutionEngine != null) {
            strategyEvolutionEngine.update(worldMemory.traces());
        }
        KPI kpi = kpiSystem == null
                ? KPI.empty()
                : kpiSystem.measure(safeGoal, planningOutcome, executionResult, evaluation, organization, orgMemory);
        return new OrganizationAssessment(evaluation, kpi, worldTrace);
    }
}
