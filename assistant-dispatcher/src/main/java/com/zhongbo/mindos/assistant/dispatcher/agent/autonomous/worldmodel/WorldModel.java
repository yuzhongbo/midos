package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WorldModel {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final WorldMemory worldMemory;

    @Autowired
    public WorldModel(DispatcherMemoryFacade dispatcherMemoryFacade,
                      WorldMemory worldMemory) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.worldMemory = worldMemory;
    }

    public PredictionResult predict(TaskGraph graph) {
        return predict(graph, AutonomousPlanningContext.empty());
    }

    public PredictionResult predict(TaskGraph graph, AutonomousPlanningContext context) {
        TaskGraph safeGraph = graph == null ? new TaskGraph(List.of(), List.of()) : graph;
        if (safeGraph.isEmpty() || safeGraph.nodes().isEmpty()) {
            return new PredictionResult(0.0, 1.0, 1.0, 1.0);
        }
        String strategyType = strategyTypeOf(safeGraph);
        String agentId = agentIdOf(safeGraph);
        Map<String, SkillUsageStats> statsByTarget = statsByTarget(AutonomousPlanningContext.safe(context).userId());
        double historicalSuccess = historicalSuccess(safeGraph, statsByTarget);
        double complexityPenalty = complexityPenalty(safeGraph);
        double calibrationOffset = worldMemory == null
                ? 0.0
                : clampSigned(worldMemory.averageErrorGap(agentId) * 0.35 + averageTargetGap(safeGraph) * 0.15);
        double successProbability = clamp(historicalSuccess
                + strategySuccessBias(strategyType)
                - complexityPenalty
                + calibrationOffset);
        double cost = clamp(costScore(safeGraph, strategyType));
        double latency = clamp(latencyScore(safeGraph, strategyType));
        double risk = clamp(1.0 - successProbability + strategyRiskBias(strategyType) + parallelismPenalty(safeGraph));
        return new PredictionResult(successProbability, cost, latency, risk);
    }

    private Map<String, SkillUsageStats> statsByTarget(String userId) {
        if (dispatcherMemoryFacade == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, SkillUsageStats> statsByTarget = new LinkedHashMap<>();
        for (SkillUsageStats stats : dispatcherMemoryFacade.getSkillUsageStats(userId)) {
            if (stats == null || stats.skillName() == null || stats.skillName().isBlank()) {
                continue;
            }
            statsByTarget.put(normalize(stats.skillName()), stats);
        }
        return statsByTarget.isEmpty() ? Map.of() : Map.copyOf(statsByTarget);
    }

    private double historicalSuccess(TaskGraph graph, Map<String, SkillUsageStats> statsByTarget) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (TaskNode node : graph.nodes()) {
            if (node == null || node.target().isBlank()) {
                continue;
            }
            SkillUsageStats stats = statsByTarget.get(normalize(node.target()));
            double successRate = stats == null || stats.totalCount() <= 0L
                    ? 0.65
                    : (stats.successCount() + 1.0) / (stats.totalCount() + 2.0);
            double weight = node.optional() ? 0.4 : 1.0;
            weightedSum += successRate * weight;
            totalWeight += weight;
        }
        return totalWeight <= 0.0 ? 0.65 : weightedSum / totalWeight;
    }

    private double complexityPenalty(TaskGraph graph) {
        int nodeCount = graph.nodes().size();
        long mcpCount = graph.nodes().stream().filter(node -> node != null && node.target().startsWith("mcp.")).count();
        long retryBudget = graph.nodes().stream().mapToLong(node -> Math.max(0, node.maxAttempts() - 1)).sum();
        double penalty = Math.max(0, nodeCount - 1) * 0.05
                + mcpCount * 0.05
                + retryBudget * 0.02
                + parallelismPenalty(graph) * 0.2;
        return clamp(penalty);
    }

    private double costScore(TaskGraph graph, String strategyType) {
        int nodeCount = graph.nodes().size();
        long mcpCount = graph.nodes().stream().filter(node -> node != null && node.target().startsWith("mcp.")).count();
        long retryBudget = graph.nodes().stream().mapToLong(node -> Math.max(0, node.maxAttempts() - 1)).sum();
        double base = nodeCount * 0.12 + mcpCount * 0.15 + retryBudget * 0.03;
        return base + switch (strategyType) {
            case "conservative" -> 0.12;
            case "aggressive" -> 0.08;
            default -> 0.10;
        };
    }

    private double latencyScore(TaskGraph graph, String strategyType) {
        int depth = estimatedDepth(graph);
        long mcpCount = graph.nodes().stream().filter(node -> node != null && node.target().startsWith("mcp.")).count();
        long retryBudget = graph.nodes().stream().mapToLong(node -> Math.max(0, node.maxAttempts() - 1)).sum();
        double base = depth * 0.12 + mcpCount * 0.18 + retryBudget * 0.03 - parallelRootCount(graph) * 0.02;
        return base + switch (strategyType) {
            case "conservative" -> 0.10;
            case "aggressive" -> 0.05;
            default -> 0.08;
        };
    }

    private double strategySuccessBias(String strategyType) {
        return switch (strategyType) {
            case "conservative" -> 0.08;
            case "aggressive" -> -0.04;
            default -> 0.02;
        };
    }

    private double strategyRiskBias(String strategyType) {
        return switch (strategyType) {
            case "conservative" -> 0.02;
            case "aggressive" -> 0.14;
            default -> 0.08;
        };
    }

    private double averageTargetGap(TaskGraph graph) {
        if (worldMemory == null) {
            return 0.0;
        }
        return graph.nodes().stream()
                .filter(node -> node != null && node.target() != null && !node.target().isBlank())
                .mapToDouble(node -> worldMemory.averageErrorGapForTarget(node.target()))
                .average()
                .orElse(0.0);
    }

    private double parallelismPenalty(TaskGraph graph) {
        return clamp(Math.max(0, parallelRootCount(graph) - 1) * 0.05);
    }

    private int parallelRootCount(TaskGraph graph) {
        int roots = 0;
        for (TaskNode node : graph.nodes()) {
            if (node != null && (node.dependsOn() == null || node.dependsOn().isEmpty())) {
                roots++;
            }
        }
        return Math.max(1, roots);
    }

    private int estimatedDepth(TaskGraph graph) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        int maxDepth = 1;
        for (TaskNode node : graph.nodes()) {
            if (node == null) {
                continue;
            }
            int depth = 1;
            for (String dependency : node.dependsOn()) {
                depth = Math.max(depth, depths.getOrDefault(dependency, 1) + 1);
            }
            depths.put(node.id(), depth);
            maxDepth = Math.max(maxDepth, depth);
        }
        return maxDepth;
    }

    private String agentIdOf(TaskGraph graph) {
        return planMetadata(graph, "plannerAgentId", "balanced-planner");
    }

    private String strategyTypeOf(TaskGraph graph) {
        return planMetadata(graph, "plannerStrategyType", "balanced");
    }

    private String planMetadata(TaskGraph graph, String key, String fallback) {
        if (graph == null || graph.nodes() == null) {
            return fallback;
        }
        for (TaskNode node : graph.nodes()) {
            if (node == null || node.params() == null) {
                continue;
            }
            Object value = node.params().get(key);
            if (value != null && !String.valueOf(value).trim().isBlank()) {
                return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            }
        }
        return fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double clampSigned(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(-0.35, Math.min(0.35, value));
    }
}
