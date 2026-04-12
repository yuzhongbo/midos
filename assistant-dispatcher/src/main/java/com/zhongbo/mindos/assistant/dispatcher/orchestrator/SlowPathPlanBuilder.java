package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchCandidate;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanningRequest;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class SlowPathPlanBuilder {

    private final CandidateChainBuilder candidateChainBuilder;
    private final Supplier<SearchPlanner> searchPlannerSupplier;
    private final SlowPathBridge bridge;

    SlowPathPlanBuilder(CandidateChainBuilder candidateChainBuilder,
                        Supplier<SearchPlanner> searchPlannerSupplier,
                        SlowPathBridge bridge) {
        this.candidateChainBuilder = candidateChainBuilder;
        this.searchPlannerSupplier = searchPlannerSupplier;
        this.bridge = bridge;
    }

    TaskPlan buildTaskPlan(Decision decision,
                           Map<String, Object> effectiveParams,
                           OrchestrationRequest request,
                           String excludeSkill,
                           boolean requireMultipleSteps) {
        TaskPlan explicitPlan = TaskPlan.from(effectiveParams);
        if ("task.plan".equalsIgnoreCase(decision == null ? "" : decision.target()) || !explicitPlan.isEmpty()) {
            return explicitPlan;
        }
        if (decision == null
                || decision.target() == null
                || decision.target().isBlank()
                || bridge.isMcpSkill(decision.target())) {
            return new TaskPlan(List.of());
        }
        List<ScoredCandidate> candidates = candidateChainBuilder.build(decision.target(), request).stream()
                .filter(candidate -> candidate != null && candidate.skillName() != null && !candidate.skillName().isBlank())
                .filter(candidate -> !bridge.isMcpSkill(candidate.skillName()))
                .filter(candidate -> excludeSkill == null || excludeSkill.isBlank() || !excludeSkill.equals(candidate.skillName()))
                .filter(candidate -> !requireMultipleSteps || !candidate.skillName().equals(decision.target()))
                .toList();
        if (candidates.isEmpty()) {
            return new TaskPlan(List.of());
        }
        List<TaskStep> steps = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int index = 1;
        for (ScoredCandidate candidate : candidates) {
            if (!seen.add(candidate.skillName())) {
                continue;
            }
            Map<String, Object> stepParams = steps.isEmpty() ? (effectiveParams == null ? Map.of() : effectiveParams) : Map.of();
            steps.add(new TaskStep("auto-step-" + index, candidate.skillName(), stepParams, "auto" + index, false));
            index++;
            if (steps.size() >= 3) {
                break;
            }
        }
        if (requireMultipleSteps && steps.size() < 2) {
            return new TaskPlan(List.of());
        }
        return new TaskPlan(steps);
    }

    TaskGraph buildTaskGraph(Decision decision,
                             Map<String, Object> effectiveParams,
                             OrchestrationRequest request,
                             String excludeSkill,
                             boolean requireMultipleSteps) {
        TaskGraph explicitGraph = TaskGraph.fromDsl(effectiveParams);
        if (!explicitGraph.isEmpty()) {
            return explicitGraph;
        }
        TaskGraph searchGraph = buildSearchTaskGraph(decision, effectiveParams, request, excludeSkill, requireMultipleSteps);
        if (!searchGraph.isEmpty()) {
            return searchGraph;
        }
        if (decision == null
                || decision.target() == null
                || decision.target().isBlank()
                || bridge.isMcpSkill(decision.target())) {
            return new TaskGraph(List.of(), List.of());
        }
        List<ScoredCandidate> candidates = candidateChainBuilder.build(decision.target(), request).stream()
                .filter(candidate -> candidate != null && candidate.skillName() != null && !candidate.skillName().isBlank())
                .filter(candidate -> !bridge.isMcpSkill(candidate.skillName()))
                .filter(candidate -> excludeSkill == null || excludeSkill.isBlank() || !excludeSkill.equals(candidate.skillName()))
                .filter(candidate -> !requireMultipleSteps || !candidate.skillName().equals(decision.target()))
                .toList();
        if (candidates.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<String> path = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ScoredCandidate candidate : candidates) {
            if (!seen.add(candidate.skillName())) {
                continue;
            }
            path.add(candidate.skillName());
            if (path.size() >= 3) {
                break;
            }
        }
        if (requireMultipleSteps && path.size() < 2) {
            return new TaskGraph(List.of(), List.of());
        }
        TaskGraph linearGraph = TaskGraph.linear(path, effectiveParams);
        return new TaskGraph(new ArrayList<>(linearGraph.nodes()), linearGraph.edges());
    }

    Map<String, Object> attachTaskGraph(Map<String, Object> effectiveParams, TaskGraph taskGraph) {
        Map<String, Object> enriched = new LinkedHashMap<>(effectiveParams == null ? Map.of() : effectiveParams);
        enriched.put("nodes", taskGraph.nodes().stream()
                .map(node -> Map.of(
                        "id", node.id(),
                        "target", node.target(),
                        "params", node.params(),
                        "saveAs", node.saveAs(),
                        "optional", node.optional(),
                        "dependsOn", node.dependsOn()))
                .toList());
        enriched.put("edges", taskGraph.edges().stream()
                .map(edge -> Map.of("from", edge.from(), "to", edge.to()))
                .toList());
        enriched.put("tasks", taskGraph.nodes().stream()
                .map(node -> Map.of(
                        "id", node.id(),
                        "target", node.target(),
                        "params", node.params(),
                        "saveAs", node.saveAs(),
                        "optional", node.optional(),
                        "dependsOn", node.dependsOn()))
                .toList());
        return Map.copyOf(enriched);
    }

    Map<String, Object> attachTaskPlan(Map<String, Object> effectiveParams, TaskPlan taskPlan) {
        Map<String, Object> enriched = new LinkedHashMap<>(effectiveParams == null ? Map.of() : effectiveParams);
        enriched.put("tasks", taskPlan.steps().stream()
                .map(step -> Map.of(
                        "id", step.id(),
                        "target", step.target(),
                        "params", step.params(),
                        "saveAs", step.saveAs(),
                        "optional", step.optional()))
                .toList());
        return Map.copyOf(enriched);
    }

    interface SlowPathBridge {
        boolean isMcpSkill(String target);
    }

    private TaskGraph buildSearchTaskGraph(Decision decision,
                                           Map<String, Object> effectiveParams,
                                           OrchestrationRequest request,
                                           String excludeSkill,
                                           boolean requireMultipleSteps) {
        SearchPlanner searchPlanner = searchPlannerSupplier.get();
        if (searchPlanner == null || decision == null || decision.target() == null || decision.target().isBlank()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<SearchCandidate> candidates = searchPlanner.search(new SearchPlanningRequest(
                request == null ? "" : request.userId(),
                request == null ? "" : request.userInput(),
                decision.target(),
                effectiveParams,
                3,
                3
        ));
        if (candidates.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<String> path = candidates.get(0).path().stream()
                .filter(skill -> excludeSkill == null || excludeSkill.isBlank() || !excludeSkill.equals(skill))
                .filter(skill -> !requireMultipleSteps || !decision.target().equals(skill))
                .distinct()
                .limit(3)
                .toList();
        if (path.isEmpty() || (requireMultipleSteps && path.size() < 2)) {
            return new TaskGraph(List.of(), List.of());
        }
        return TaskGraph.linear(path, effectiveParams);
    }
}
