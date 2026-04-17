package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillRecipe;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillRecipeFailurePolicy;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillRecipeRegistry;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillRecipeSelector;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillRecipeStep;
import com.zhongbo.mindos.assistant.dispatcher.system.SkillCompositionService;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.learning.GeneratedSkillReview;
import com.zhongbo.mindos.assistant.skill.learning.ToolLearningService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

final class HermesEvolutionLoopService {

    private static final Logger LOGGER = Logger.getLogger(HermesEvolutionLoopService.class.getName());

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DispatcherMemoryCommandService memoryCommandService;
    private final SkillCostTelemetry skillCostTelemetry;
    private final HermesDecisionPolicy decisionPolicy;
    private final SkillRecipeRegistry skillRecipeRegistry;
    private final ToolLearningService toolLearningService;
    private final ExecutorService evolutionExecutor;
    private final ConcurrentHashMap<String, Instant> lastRunByUser = new ConcurrentHashMap<>();
    private final long minRunIntervalSeconds;
    private final long minUsageSamples;
    private final int maxRecipeEvidenceNodes;
    private final double minDetailHitRate;

    HermesEvolutionLoopService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               SkillCostTelemetry skillCostTelemetry,
                               HermesDecisionPolicy decisionPolicy,
                               SkillRecipeRegistry skillRecipeRegistry) {
        this(dispatcherMemoryFacade, null, skillCostTelemetry, decisionPolicy, skillRecipeRegistry, null,
                45L, 3L, 12, 0.40d);
    }

    HermesEvolutionLoopService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               DispatcherMemoryCommandService memoryCommandService,
                               SkillCostTelemetry skillCostTelemetry,
                               HermesDecisionPolicy decisionPolicy,
                               SkillRecipeRegistry skillRecipeRegistry) {
        this(dispatcherMemoryFacade, memoryCommandService, skillCostTelemetry, decisionPolicy, skillRecipeRegistry, null,
                45L, 3L, 12, 0.40d);
    }

    HermesEvolutionLoopService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               SkillCostTelemetry skillCostTelemetry,
                               HermesDecisionPolicy decisionPolicy,
                               SkillRecipeRegistry skillRecipeRegistry,
                               long minRunIntervalSeconds,
                               long minUsageSamples,
                               int maxRecipeEvidenceNodes,
                               double minDetailHitRate) {
        this(dispatcherMemoryFacade, null, skillCostTelemetry, decisionPolicy, skillRecipeRegistry, null,
                minRunIntervalSeconds, minUsageSamples, maxRecipeEvidenceNodes, minDetailHitRate);
    }

    HermesEvolutionLoopService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               DispatcherMemoryCommandService memoryCommandService,
                               SkillCostTelemetry skillCostTelemetry,
                               HermesDecisionPolicy decisionPolicy,
                               SkillRecipeRegistry skillRecipeRegistry,
                               long minRunIntervalSeconds,
                               long minUsageSamples,
                               int maxRecipeEvidenceNodes,
                               double minDetailHitRate) {
        this(dispatcherMemoryFacade, memoryCommandService, skillCostTelemetry, decisionPolicy, skillRecipeRegistry, null,
                minRunIntervalSeconds, minUsageSamples, maxRecipeEvidenceNodes, minDetailHitRate);
    }

    HermesEvolutionLoopService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               DispatcherMemoryCommandService memoryCommandService,
                               SkillCostTelemetry skillCostTelemetry,
                               HermesDecisionPolicy decisionPolicy,
                               SkillRecipeRegistry skillRecipeRegistry,
                               ToolLearningService toolLearningService) {
        this(dispatcherMemoryFacade, memoryCommandService, skillCostTelemetry, decisionPolicy, skillRecipeRegistry,
                toolLearningService, 45L, 3L, 12, 0.40d);
    }

    HermesEvolutionLoopService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               DispatcherMemoryCommandService memoryCommandService,
                               SkillCostTelemetry skillCostTelemetry,
                               HermesDecisionPolicy decisionPolicy,
                               SkillRecipeRegistry skillRecipeRegistry,
                               ToolLearningService toolLearningService,
                               long minRunIntervalSeconds,
                               long minUsageSamples,
                               int maxRecipeEvidenceNodes,
                               double minDetailHitRate) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.memoryCommandService = memoryCommandService;
        this.skillCostTelemetry = skillCostTelemetry;
        this.decisionPolicy = decisionPolicy;
        this.skillRecipeRegistry = skillRecipeRegistry == null ? new SkillRecipeRegistry() : skillRecipeRegistry;
        this.toolLearningService = toolLearningService;
        this.minRunIntervalSeconds = Math.max(5L, minRunIntervalSeconds);
        this.minUsageSamples = Math.max(1L, minUsageSamples);
        this.maxRecipeEvidenceNodes = Math.max(3, maxRecipeEvidenceNodes);
        this.minDetailHitRate = clamp(minDetailHitRate, 0.0d, 1.0d);
        this.evolutionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hermes-evolution-loop");
            thread.setDaemon(true);
            return thread;
        });
    }

    void observeAndEvolveAsync(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank() || !shouldRun(normalizedUserId)) {
            return;
        }
        CompletableFuture.runAsync(() -> evolveUser(normalizedUserId), evolutionExecutor)
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "Hermes evolution loop failed for userId=" + normalizedUserId, error);
                    return null;
                });
    }

    EvolutionReport evolveUser(String userId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank() || decisionPolicy == null) {
            return new EvolutionReport(normalizedUserId, false, false, "", List.of("missing evolution dependencies"));
        }
        Map<String, SkillUsageStats> usageStats = usageStats(normalizedUserId);
        Map<String, CostModel> costModels = costModels(normalizedUserId);
        long totalObservations = usageStats.values().stream().mapToLong(SkillUsageStats::totalCount).sum();
        if (totalObservations < minUsageSamples && costModels.isEmpty()) {
            return new EvolutionReport(normalizedUserId, false, false, "", List.of("insufficient observations"));
        }

        HermesRuntimePolicySnapshot snapshot = buildPolicySnapshot(normalizedUserId, usageStats, costModels);
        decisionPolicy.deployRuntimePolicy(normalizedUserId, snapshot);
        boolean recipeChanged = deployRecipeOverrides(normalizedUserId);
        int materializedEdges = materializePreferredSkillEdges(normalizedUserId, usageStats);
        int reviewedDrafts = proposeReviewedSkillEvolutions(normalizedUserId, usageStats);
        lastRunByUser.put(normalizedUserId, Instant.now());
        return new EvolutionReport(
                normalizedUserId,
                true,
                recipeChanged,
                snapshot.snapshotId(),
                List.of(
                        "policy-source=" + snapshot.source(),
                        "policy-adjustments=" + snapshot.skillScoreAdjustments().size(),
                        "recipe-overrides=" + recipeChanged,
                        "materialized-edges=" + materializedEdges,
                        "skill-evolution-drafts=" + reviewedDrafts
                )
        );
    }

    private boolean shouldRun(String userId) {
        Instant now = Instant.now();
        Instant previous = lastRunByUser.get(userId);
        if (previous != null && Duration.between(previous, now).getSeconds() < minRunIntervalSeconds) {
            return false;
        }
        lastRunByUser.put(userId, now);
        return true;
    }

    private Map<String, SkillUsageStats> usageStats(String userId) {
        if (dispatcherMemoryFacade == null) {
            return Map.of();
        }
        LinkedHashMap<String, SkillUsageStats> stats = new LinkedHashMap<>();
        for (SkillUsageStats stat : dispatcherMemoryFacade.getSkillUsageStats(userId)) {
            if (stat == null || stat.skillName() == null || stat.skillName().isBlank()) {
                continue;
            }
            stats.put(stat.skillName(), stat);
        }
        return stats.isEmpty() ? Map.of() : Map.copyOf(stats);
    }

    private Map<String, CostModel> costModels(String userId) {
        if (skillCostTelemetry == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, CostModel> models = skillCostTelemetry.costModels(userId);
        return models == null || models.isEmpty() ? Map.of() : Map.copyOf(models);
    }

    private HermesRuntimePolicySnapshot buildPolicySnapshot(String userId,
                                                            Map<String, SkillUsageStats> usageStats,
                                                            Map<String, CostModel> costModels) {
        double weightedSuccessRate = weightedSuccessRate(usageStats, costModels);
        double memorySuccessBoostWeight = clamp(0.12d + weightedSuccessRate * 0.14d, 0.10d, 0.28d);
        double graphBoostWeight = dispatcherMemoryFacade != null && dispatcherMemoryFacade.hasGraphMemory()
                ? clamp(0.15d + weightedSuccessRate * 0.18d, 0.15d, 0.33d)
                : 0.18d;
        double graphContinuationBaseScore = clamp(0.68d + Math.max(0.0d, weightedSuccessRate - 0.50d) * 0.20d, 0.68d, 0.82d);

        LinkedHashMap<String, Double> adjustments = new LinkedHashMap<>();
        LinkedHashMap<String, String> adjustmentReasons = new LinkedHashMap<>();
        for (Map.Entry<String, SkillUsageStats> entry : usageStats.entrySet()) {
            String skillName = entry.getKey();
            SkillUsageStats stats = entry.getValue();
            if (stats == null || stats.totalCount() < 2) {
                continue;
            }
            CostModel costModel = costModels.getOrDefault(skillName, CostModel.neutral());
            double adjustment = 0.0d;
            String reason = "";
            if (costModel.successRate() >= 0.80d && costModel.cost() <= 0.45d) {
                adjustment = 0.06d;
                reason = "policy favors the lower-cost higher-success route";
            } else if (costModel.successRate() < 0.55d) {
                adjustment = -0.08d;
                reason = "policy penalizes the low-success route";
            } else if (costModel.cost() >= 0.80d && costModel.successRate() < 0.70d) {
                adjustment = -0.05d;
                reason = "policy penalizes the high-cost unstable route";
            }
            if (adjustment != 0.0d) {
                adjustments.put(skillName, adjustment);
                adjustmentReasons.put(skillName, reason);
            }
        }
        List<String> reconstructionActions = buildReconstructionActions(adjustments, usageStats, weightedSuccessRate);
        return new HermesRuntimePolicySnapshot(
                "policy-evolved-" + userId + "-" + Instant.now().toEpochMilli(),
                "stage5-evolution-loop",
                Instant.now(),
                memorySuccessBoostWeight,
                graphBoostWeight,
                graphContinuationBaseScore,
                HermesRuntimePolicySnapshot.defaults().searchPriorityLeadBoost(),
                HermesRuntimePolicySnapshot.defaults().builtinNewsLeadBoost(),
                buildStrategySummary(weightedSuccessRate, adjustments, reconstructionActions),
                reconstructionActions,
                adjustments,
                adjustmentReasons
        );
    }

    private String buildStrategySummary(double weightedSuccessRate,
                                        Map<String, Double> adjustments,
                                        List<String> reconstructionActions) {
        StringBuilder summary = new StringBuilder("self-reconstruction: successRate=");
        summary.append(String.format(Locale.ROOT, "%.2f", weightedSuccessRate));
        summary.append(", skillAdjustments=").append(adjustments == null ? 0 : adjustments.size());
        if (reconstructionActions != null && !reconstructionActions.isEmpty()) {
            summary.append(", actions=").append(String.join(" | ", reconstructionActions));
        }
        return summary.toString();
    }

    private List<String> buildReconstructionActions(Map<String, Double> adjustments,
                                                    Map<String, SkillUsageStats> usageStats,
                                                    double weightedSuccessRate) {
        List<String> actions = new ArrayList<>();
        if (weightedSuccessRate < 0.60d) {
            actions.add("tighten routing toward higher-success skills");
        } else {
            actions.add("reinforce graph-guided routing for stable skills");
        }
        if (adjustments != null && !adjustments.isEmpty()) {
            adjustments.entrySet().stream()
                    .sorted((left, right) -> Double.compare(Math.abs(right.getValue()), Math.abs(left.getValue())))
                    .limit(2)
                    .forEach(entry -> actions.add((entry.getValue() >= 0.0d ? "boost " : "deprioritize ") + entry.getKey()));
        }
        long stableSkills = usageStats == null ? 0L : usageStats.values().stream()
                .filter(stats -> stats != null && stats.totalCount() >= minUsageSamples)
                .filter(stats -> stats.successCount() / (double) Math.max(1L, stats.totalCount()) >= 0.80d)
                .count();
        if (stableSkills > 0) {
            actions.add("materialize stable skill graph edges");
        }
        return actions.isEmpty() ? List.of("preserve current runtime policy") : List.copyOf(actions);
    }

    private double weightedSuccessRate(Map<String, SkillUsageStats> usageStats, Map<String, CostModel> costModels) {
        if (usageStats == null || usageStats.isEmpty()) {
            if (costModels == null || costModels.isEmpty()) {
                return 0.5d;
            }
            return clamp(costModels.values().stream().mapToDouble(CostModel::successRate).average().orElse(0.5d), 0.0d, 1.0d);
        }
        long total = 0L;
        double success = 0.0d;
        for (SkillUsageStats stat : usageStats.values()) {
            if (stat == null || stat.totalCount() <= 0) {
                continue;
            }
            total += stat.totalCount();
            success += stat.successCount();
        }
        if (total <= 0L) {
            return 0.5d;
        }
        return clamp(success / total, 0.0d, 1.0d);
    }

    private boolean deployRecipeOverrides(String userId) {
        List<SkillRecipe> baseRecipes = new ArrayList<>(skillRecipeRegistry.activeRecipes());
        boolean changed = false;
        changed |= maybeReplaceWithDirectRecipe(
                baseRecipes,
                userId,
                "web.lookup.detail",
                "web.lookup",
                SkillRecipeSelector.GENERIC_WEB_SEARCH_PATTERN,
                SkillCompositionService.WEB_LOOKUP_DETAIL_WORKFLOW_REASON
        );
        changed |= maybeReplaceWithDirectRecipe(
                baseRecipes,
                userId,
                "docs.lookup.detail",
                "docs.lookup",
                "mcp.docs.searchdocs",
                SkillCompositionService.DOCS_LOOKUP_DETAIL_WORKFLOW_REASON
        );
        boolean hadOverride = !skillRecipeRegistry.activeRecipes(userId).equals(skillRecipeRegistry.activeRecipes());
        if (changed) {
            skillRecipeRegistry.deploy(userId, baseRecipes);
            return true;
        }
        if (hadOverride) {
            skillRecipeRegistry.clear(userId);
            return true;
        }
        return false;
    }

    private int materializePreferredSkillEdges(String userId, Map<String, SkillUsageStats> usageStats) {
        if (dispatcherMemoryFacade == null || memoryCommandService == null || usageStats == null || usageStats.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (SkillUsageStats stats : usageStats.values()) {
            if (stats == null || stats.skillName() == null || stats.skillName().isBlank() || stats.totalCount() < 3) {
                continue;
            }
            double successRate = stats.successCount() / (double) stats.totalCount();
            if (successRate < 0.80d) {
                continue;
            }
            List<MemoryNode> relatedNodes = dispatcherMemoryFacade.searchGraphNodes(userId, stats.skillName(), 8);
            MemoryNode skillNode = relatedNodes.stream()
                    .filter(node -> node != null && "hermes.skill".equals(node.type()))
                    .filter(node -> stats.skillName().equals(stringValue(node.data().get("skillName"))))
                    .findFirst()
                    .orElse(null);
            if (skillNode == null) {
                continue;
            }
            for (MemoryNode taskNode : relatedNodes.stream()
                    .filter(node -> node != null && "hermes.task".equals(node.type()))
                    .filter(node -> stats.skillName().equals(stringValue(node.data().get("skillName"))))
                    .toList()) {
                memoryCommandService.linkGraph(
                        userId,
                        taskNode.id(),
                        "prefers-skill",
                        skillNode.id(),
                        successRate,
                        Map.of(
                                "source", "stage5-evolution-loop",
                                "skillName", stats.skillName(),
                                "successRate", successRate
                        )
                );
                created++;
            }
        }
        return created;
    }

    private int proposeReviewedSkillEvolutions(String userId, Map<String, SkillUsageStats> usageStats) {
        if (toolLearningService == null || usageStats == null || usageStats.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (SkillUsageStats stats : usageStats.values()) {
            if (stats == null || stats.skillName() == null || stats.skillName().isBlank()) {
                continue;
            }
            if (!stats.skillName().startsWith("generated.") || stats.totalCount() < 2) {
                continue;
            }
            double successRate = stats.totalCount() <= 0 ? 0.0d : stats.successCount() / (double) stats.totalCount();
            if (successRate >= 0.60d) {
                continue;
            }
            try {
                GeneratedSkillReview review = toolLearningService.evolveReviewedDraft(
                        userId,
                        stats.skillName(),
                        "Improve stability, input validation, and clearer failure reporting for repeated runtime use."
                );
                if (review != null && review.valid()) {
                    created++;
                }
            } catch (RuntimeException error) {
                LOGGER.log(Level.FINE, "Skipped reviewed skill evolution for " + stats.skillName(), error);
            }
        }
        return created;
    }

    private boolean maybeReplaceWithDirectRecipe(List<SkillRecipe> recipes,
                                                 String userId,
                                                 String recipeId,
                                                 String decisionTarget,
                                                 String executionTargetPattern,
                                                 String workflowReason) {
        if (dispatcherMemoryFacade == null || recipes == null || recipes.isEmpty()) {
            return false;
        }
        List<MemoryNode> evidence = dispatcherMemoryFacade.searchGraphNodes(userId, recipeId, maxRecipeEvidenceNodes).stream()
                .filter(node -> node != null && "hermes.execution".equals(node.type()))
                .filter(node -> recipeId.equals(stringValue(node.data().get("workflowRecipe"))))
                .toList();
        if (evidence.size() < minUsageSamples) {
            return false;
        }
        long detailHits = evidence.stream()
                .filter(node -> !stringValue(node.data().get("detailTitle")).isBlank())
                .count();
        double detailHitRate = detailHits / (double) evidence.size();
        if (detailHitRate >= minDetailHitRate) {
            return false;
        }
        SkillRecipe directRecipe = new SkillRecipe(
                recipeId,
                new SkillRecipeSelector(decisionTarget, executionTargetPattern),
                "skill-graph",
                workflowReason + ":degraded",
                List.of(new SkillRecipeStep(
                        "execute",
                        SkillRecipeSelector.EXECUTION_TARGET_TOKEN,
                        SkillRecipeFailurePolicy.STOP,
                        Map.of()
                )),
                true,
                1
        );
        for (int index = 0; index < recipes.size(); index++) {
            if (recipeId.equals(recipes.get(index).id())) {
                recipes.set(index, directRecipe);
                return true;
            }
        }
        recipes.add(directRecipe);
        return true;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    record EvolutionReport(String userId,
                           boolean policyUpdated,
                           boolean recipesUpdated,
                           String snapshotId,
                           List<String> reasons) {
    }
}
