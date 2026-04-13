package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class MultiAgentCoordinator {

    private final List<PlannerAgent> plannerAgents;
    private final WorldModel worldModel;
    private final PlanEvaluator planEvaluator;
    private final StrategyEvolutionEngine strategyEvolutionEngine;

    @Autowired
    public MultiAgentCoordinator(List<PlannerAgent> plannerAgents,
                                 WorldModel worldModel,
                                 PlanEvaluator planEvaluator,
                                 StrategyEvolutionEngine strategyEvolutionEngine) {
        this.plannerAgents = plannerAgents == null ? List.of() : List.copyOf(plannerAgents);
        this.worldModel = worldModel;
        this.planEvaluator = planEvaluator;
        this.strategyEvolutionEngine = strategyEvolutionEngine;
    }

    public PlanSelection selectBestPlan(Goal goal, AutonomousPlanningContext context) {
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        List<PlanProposal> proposals = new ArrayList<>();
        for (PlannerAgent plannerAgent : plannerAgents) {
            if (plannerAgent == null) {
                continue;
            }
            TaskGraph graph = plannerAgent.plan(goal, safeContext);
            if (graph == null || graph.isEmpty()) {
                continue;
            }
            PredictionResult prediction = worldModel == null
                    ? new PredictionResult(0.0, 1.0, 1.0, 1.0)
                    : worldModel.predict(graph, safeContext);
            PlanScore score = planEvaluator == null
                    ? new PlanScore(0.0, 0.0)
                    : planEvaluator.evaluate(graph, prediction);
            double strategyWeight = strategyEvolutionEngine == null ? 0.5 : strategyEvolutionEngine.weightOf(plannerAgent.agentId());
            proposals.add(new PlanProposal(
                    plannerAgent.agentId(),
                    plannerAgent.strategyType(),
                    graph,
                    prediction,
                    score,
                    strategyWeight,
                    Instant.now()
            ));
        }
        if (proposals.isEmpty()) {
            return PlanSelection.empty();
        }
        proposals.sort(Comparator
                .comparingDouble((PlanProposal proposal) -> proposal.score().score()).reversed()
                .thenComparing(Comparator.comparingDouble(PlanProposal::strategyWeight).reversed())
                .thenComparing(Comparator.comparingDouble((PlanProposal proposal) -> proposal.prediction().successProbability()).reversed())
                .thenComparing(Comparator.comparingDouble((PlanProposal proposal) -> 1.0 - proposal.prediction().risk()).reversed())
                .thenComparing(PlanProposal::agentId));
        PlanProposal winner = proposals.get(0);
        return new PlanSelection(
                winner.agentId(),
                winner.strategyType(),
                winner.graph(),
                winner.prediction(),
                winner.score(),
                List.copyOf(proposals),
                buildSummary(winner, proposals)
        );
    }

    public TaskGraph selectBestPlanGraph(Goal goal, AutonomousPlanningContext context) {
        return selectBestPlan(goal, context).graph();
    }

    private String buildSummary(PlanProposal winner, List<PlanProposal> proposals) {
        return "winner=" + winner.agentId()
                + ",strategy=" + winner.strategyType()
                + ",score=" + round(winner.score().score())
                + ",successProbability=" + round(winner.prediction().successProbability())
                + ",candidates=" + proposals.size();
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record PlanProposal(String agentId,
                               String strategyType,
                               TaskGraph graph,
                               PredictionResult prediction,
                               PlanScore score,
                               double strategyWeight,
                               Instant createdAt) {

        public PlanProposal {
            agentId = agentId == null ? "" : agentId.trim();
            strategyType = strategyType == null ? "balanced" : strategyType.trim();
            strategyWeight = clamp(strategyWeight);
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }

        private static double clamp(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    public record PlanSelection(String agentId,
                                String strategyType,
                                TaskGraph graph,
                                PredictionResult prediction,
                                PlanScore score,
                                List<PlanProposal> proposals,
                                String summary) {

        public PlanSelection {
            agentId = agentId == null ? "" : agentId.trim();
            strategyType = strategyType == null ? "balanced" : strategyType.trim();
            graph = graph == null ? new TaskGraph(List.of(), List.of()) : graph;
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
            summary = summary == null ? "" : summary.trim();
        }

        public static PlanSelection empty() {
            return new PlanSelection("", "balanced", new TaskGraph(List.of(), List.of()), new PredictionResult(0.0, 1.0, 1.0, 1.0), new PlanScore(0.0, 0.0), List.of(), "no-plan");
        }

        public boolean hasPlan() {
            return graph != null && !graph.isEmpty();
        }

        public Map<String, Object> asMap() {
            return Map.of(
                    "agentId", agentId,
                    "strategyType", strategyType,
                    "prediction", prediction,
                    "score", score,
                    "summary", summary
            );
        }
    }
}
