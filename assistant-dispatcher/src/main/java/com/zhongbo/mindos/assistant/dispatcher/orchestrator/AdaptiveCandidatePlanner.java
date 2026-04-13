package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AdaptiveCandidatePlanner implements CandidatePlanner {

    private static final double DEFAULT_LATENCY_WEIGHT = 0.10;
    private static final double DEFAULT_LEARNING_RATE = 0.18;
    private static final double DEFAULT_MEMORY_SATURATION = 8.0;

    protected final SkillCatalogFacade skillEngine;
    protected final DispatcherMemoryFacade dispatcherMemoryFacade;
    protected final SkillCostTelemetry skillCostTelemetry;
    protected final PlannerLearningStore plannerLearningStore;
    protected final int maxCandidates;
    protected final double explicitWeight;
    protected final double keywordWeight;
    protected final double memoryWeight;
    protected final double successWeight;
    protected final double latencyWeight;
    protected final double learningRate;
    protected final double memorySaturation;

    public AdaptiveCandidatePlanner() {
        this(
                null,
                new DispatcherMemoryFacade(null, null, (ProceduralMemory) null),
                (SkillCostTelemetry) null,
                (PlannerLearningStore) null,
                3,
                0.40,
                0.35,
                0.15,
                0.10,
                DEFAULT_LATENCY_WEIGHT,
                DEFAULT_LEARNING_RATE,
                DEFAULT_MEMORY_SATURATION
        );
    }

    public AdaptiveCandidatePlanner(SkillCatalogFacade skillEngine,
                                    DispatcherMemoryFacade dispatcherMemoryFacade,
                                    int maxCandidates,
                                    double explicitWeight,
                                    double keywordWeight,
                                    double memoryWeight,
                                    double successWeight) {
        this(
                skillEngine,
                dispatcherMemoryFacade,
                (SkillCostTelemetry) null,
                (PlannerLearningStore) null,
                maxCandidates,
                explicitWeight,
                keywordWeight,
                memoryWeight,
                successWeight,
                DEFAULT_LATENCY_WEIGHT,
                DEFAULT_LEARNING_RATE,
                DEFAULT_MEMORY_SATURATION
        );
    }

    public AdaptiveCandidatePlanner(SkillCatalogFacade skillEngine,
                                    DispatcherMemoryFacade dispatcherMemoryFacade,
                                    SkillCostTelemetry skillCostTelemetry,
                                    int maxCandidates,
                                    double explicitWeight,
                                    double keywordWeight,
                                    double memoryWeight,
                                    double successWeight) {
        this(
                skillEngine,
                dispatcherMemoryFacade,
                skillCostTelemetry,
                null,
                maxCandidates,
                explicitWeight,
                keywordWeight,
                memoryWeight,
                successWeight,
                DEFAULT_LATENCY_WEIGHT,
                DEFAULT_LEARNING_RATE,
                DEFAULT_MEMORY_SATURATION
        );
    }

    protected AdaptiveCandidatePlanner(SkillCatalogFacade skillEngine,
                                       DispatcherMemoryFacade dispatcherMemoryFacade,
                                       SkillCostTelemetry skillCostTelemetry,
                                       PlannerLearningStore plannerLearningStore,
                                       int maxCandidates,
                                       double explicitWeight,
                                       double keywordWeight,
                                       double memoryWeight,
                                       double successWeight,
                                       double latencyWeight,
                                       double learningRate,
                                       double memorySaturation) {
        this.skillEngine = skillEngine;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade == null
                ? new DispatcherMemoryFacade(null, null, (ProceduralMemory) null)
                : dispatcherMemoryFacade;
        this.skillCostTelemetry = skillCostTelemetry;
        this.plannerLearningStore = plannerLearningStore;
        this.maxCandidates = Math.max(1, Math.min(3, maxCandidates));
        this.explicitWeight = clamp01(explicitWeight);
        this.keywordWeight = clamp01(keywordWeight);
        this.memoryWeight = clamp01(memoryWeight);
        this.successWeight = clamp01(successWeight);
        this.latencyWeight = clamp01(latencyWeight);
        this.learningRate = clamp01(learningRate);
        this.memorySaturation = Math.max(1.0, memorySaturation);
    }

    @Autowired
    public AdaptiveCandidatePlanner(SkillCatalogFacade skillEngine,
                                    DispatcherMemoryFacade dispatcherMemoryFacade,
                                    ObjectProvider<SkillCostTelemetry> skillCostTelemetryProvider,
                                    ObjectProvider<PlannerLearningStore> plannerLearningStoreProvider,
                                    @Value("${mindos.dispatcher.candidate-planner.max-candidates:3}") int maxCandidates,
                                    @Value("${mindos.dispatcher.candidate-planner.explicit-weight:0.40}") double explicitWeight,
                                    @Value("${mindos.dispatcher.candidate-planner.keyword-weight:0.35}") double keywordWeight,
                                    @Value("${mindos.dispatcher.candidate-planner.memory-weight:0.15}") double memoryWeight,
                                    @Value("${mindos.dispatcher.candidate-planner.success-weight:0.10}") double successWeight,
                                    @Value("${mindos.dispatcher.candidate-planner.latency-weight:0.10}") double latencyWeight,
                                    @Value("${mindos.dispatcher.candidate-planner.learning-rate:0.18}") double learningRate,
                                    @Value("${mindos.dispatcher.candidate-planner.memory-saturation:8.0}") double memorySaturation) {
        this(skillEngine, dispatcherMemoryFacade,
                skillCostTelemetryProvider == null ? null : skillCostTelemetryProvider.getIfAvailable(),
                plannerLearningStoreProvider == null ? null : plannerLearningStoreProvider.getIfAvailable(),
                maxCandidates,
                explicitWeight,
                keywordWeight,
                memoryWeight,
                successWeight,
                latencyWeight,
                learningRate,
                memorySaturation);
    }

    @Override
    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
        String userId = request == null ? "" : request.userId();
        String userInput = request == null ? "" : request.userInput();
        Map<String, Integer> keywordScores = detectKeywordScores(userInput);
        Map<String, SkillUsageStats> usageStats = lookupUsageStats(userId);
        Map<String, Double> averageLatencies = lookupAverageLatencies(userId);
        Map<String, CostModel> costModels = lookupCostModels(userId);
        Map<String, Double> graphScores = lookupGraphScores(userId, userInput, suggestedTarget, keywordScores.keySet(), usageStats.keySet(), costModels.keySet(), averageLatencies.keySet());
        Map<String, CandidateAccumulator> candidates = buildCandidates(suggestedTarget, keywordScores, graphScores, usageStats, costModels, averageLatencies);
        if (candidates.isEmpty()) {
            return List.of();
        }

        long maxUsageCount = usageStats.values().stream()
                .mapToLong(SkillUsageStats::totalCount)
                .max()
                .orElse(0L);

        List<ScoredCandidate> scored = new ArrayList<>();
        for (CandidateAccumulator accumulator : candidates.values()) {
            String skillName = accumulator.skillName();
            int rawKeywordScore = keywordScores.getOrDefault(skillName, skillName.equals(suggestedTarget) ? 700 : 0);
            double keywordSignal = normalizeKeywordScore(rawKeywordScore);
            boolean explicitTarget = skillName.equals(suggestedTarget) && !skillName.isBlank();
            double keywordFeature = clamp01(keywordSignal + (explicitTarget ? explicitWeight : 0.0));

            SkillUsageStats stats = usageStats.get(skillName);
            CostModel costModel = costModels.get(skillName);
            PlannerLearningStore.LearningSnapshot learning = lookupPlannerLearning(userId, skillName);
            double graphScore = graphScores.getOrDefault(skillName, 0.0);
            double rewardSignal = learning.rewardScore();
            double memorySignal = buildMemorySignal(stats, maxUsageCount, graphScore, rewardSignal);
            double successRate = resolveSuccessRate(stats, costModel);
            double failureRate = resolveFailureRate(stats, successRate);
            double latencySignal = resolveLatencySignal(skillName, averageLatencies, costModel);
            double averageLatencyMs = averageLatencies.getOrDefault(skillName, -1.0);
            WeightProfile weights = adaptWeights(stats, successRate, failureRate, memorySignal, latencySignal, rewardSignal, explicitTarget);
            double finalScore = clamp01(
                    weights.keywordWeight() * keywordFeature
                            + weights.successWeight() * successRate
                            + weights.memoryWeight() * memorySignal
                            + weights.latencyWeight() * latencySignal
            );
            List<String> reasons = buildReasons(
                    skillName,
                    suggestedTarget,
                    rawKeywordScore,
                    stats,
                    graphScore,
                    memorySignal,
                    successRate,
                    failureRate,
                    latencySignal,
                    averageLatencyMs,
                    costModel,
                    weights,
                    learning
            );
            scored.add(new ScoredCandidate(
                    skillName,
                    round(finalScore),
                    round(keywordFeature),
                    round(memorySignal),
                    round(successRate),
                    reasons
            ));
        }
        scored.sort(Comparator
                .comparingDouble(ScoredCandidate::finalScore).reversed()
                .thenComparing(ScoredCandidate::skillName));
        int safeLimit = Math.min(maxCandidates, scored.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(scored.subList(0, safeLimit));
    }

    protected Map<String, Integer> detectKeywordScores(String input) {
        if (skillEngine == null || input == null || input.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        skillEngine.detectSkillCandidates(input, 8).forEach(candidate ->
                scores.put(candidate.skillName(), candidate.score()));
        return Map.copyOf(scores);
    }

    protected Map<String, SkillUsageStats> lookupUsageStats(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, SkillUsageStats> stats = new LinkedHashMap<>();
        dispatcherMemoryFacade.getSkillUsageStats(userId).forEach(entry -> stats.put(entry.skillName(), entry));
        return Map.copyOf(stats);
    }

    protected Map<String, Double> lookupAverageLatencies(String userId) {
        if (skillCostTelemetry == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, Double> latencies = new LinkedHashMap<>();
        skillCostTelemetry.averageLatencies(userId).forEach(latencies::put);
        return Map.copyOf(latencies);
    }

    protected Map<String, CostModel> lookupCostModels(String userId) {
        if (skillCostTelemetry == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, CostModel> models = new LinkedHashMap<>();
        skillCostTelemetry.costModels(userId).forEach(models::put);
        return Map.copyOf(models);
    }

    protected PlannerLearningStore.LearningSnapshot lookupPlannerLearning(String userId, String skillName) {
        if (plannerLearningStore == null || userId == null || userId.isBlank() || skillName == null || skillName.isBlank()) {
            return PlannerLearningStore.LearningSnapshot.neutral();
        }
        return plannerLearningStore.snapshot(userId, skillName);
    }

    protected Map<String, Double> lookupGraphScores(String userId,
                                                    String userInput,
                                                    String suggestedTarget,
                                                    Set<String> keywordCandidates,
                                                    Set<String> usageCandidates,
                                                    Set<String> costCandidates,
                                                    Set<String> latencyCandidates) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        List<String> candidateNames = new ArrayList<>();
        if (keywordCandidates != null) {
            candidateNames.addAll(keywordCandidates);
        }
        if (usageCandidates != null) {
            candidateNames.addAll(usageCandidates);
        }
        if (costCandidates != null) {
            candidateNames.addAll(costCandidates);
        }
        if (latencyCandidates != null) {
            candidateNames.addAll(latencyCandidates);
        }
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            candidateNames.add(suggestedTarget);
        }
        return dispatcherMemoryFacade.scoreGraphCandidates(userId, userInput, candidateNames);
    }

    protected Map<String, CandidateAccumulator> buildCandidates(String suggestedTarget,
                                                                Map<String, Integer> keywordScores,
                                                                Map<String, Double> graphScores,
                                                                Map<String, SkillUsageStats> usageStats,
                                                                Map<String, CostModel> costModels,
                                                                Map<String, Double> averageLatencies) {
        Map<String, CandidateAccumulator> candidates = new LinkedHashMap<>();
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            candidates.put(suggestedTarget, new CandidateAccumulator(suggestedTarget));
        }
        if (keywordScores != null) {
            keywordScores.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        }
        if (graphScores != null) {
            graphScores.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        }
        if (usageStats != null) {
            usageStats.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        }
        if (costModels != null) {
            costModels.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        }
        if (averageLatencies != null) {
            averageLatencies.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        }
        return candidates;
    }

    protected double resolveSuccessRate(SkillUsageStats stats, CostModel costModel) {
        if (stats != null && stats.totalCount() > 0) {
            return clamp01(stats.successCount() / (double) stats.totalCount());
        }
        return costModel == null ? 0.5 : costModel.successRate();
    }

    protected double resolveFailureRate(SkillUsageStats stats, double successRate) {
        if (stats != null && stats.totalCount() > 0) {
            return clamp01(stats.failureCount() / (double) stats.totalCount());
        }
        return clamp01(1.0 - successRate);
    }

    protected double resolveLatencySignal(String skillName,
                                          Map<String, Double> averageLatencies,
                                          CostModel costModel) {
        Double averageLatencyMs = averageLatencies == null ? null : averageLatencies.get(skillName);
        if (averageLatencyMs != null) {
            return inverseNormalized(averageLatencyMs, 1200.0);
        }
        if (costModel != null) {
            return clamp01(1.0 - costModel.latency());
        }
        return 0.5;
    }

    protected double buildMemorySignal(SkillUsageStats stats, long maxUsageCount, double graphScore) {
        return buildMemorySignal(stats, maxUsageCount, graphScore, 0.5);
    }

    protected double buildMemorySignal(SkillUsageStats stats, long maxUsageCount, double graphScore, double rewardSignal) {
        double usageSignal = normalizeMemoryScore(stats, maxUsageCount);
        double rewardBias = clamp01(rewardSignal) - 0.5;
        return clamp01(0.58 * usageSignal + 0.42 * clamp01(graphScore) + 0.18 * rewardBias);
    }

    protected WeightProfile adaptWeights(SkillUsageStats stats,
                                         double successRate,
                                         double failureRate,
                                         double memorySignal,
                                         double latencySignal,
                                         double rewardSignal,
                                         boolean explicitTarget) {
        double totalCount = stats == null ? 0.0 : stats.totalCount();
        double historyFactor = clamp01(totalCount / memorySaturation);
        double rewardBias = rewardSignal - 0.5;
        double keywordRaw = keywordWeight * (1.0 + failureRate * learningRate * 0.30 + (historyFactor < 0.25 ? 0.10 : 0.0) + Math.max(0.0, -rewardBias) * 0.10);
        if (explicitTarget) {
            keywordRaw += explicitWeight * 0.10;
        }
        double successRaw = successWeight * (1.0 + successRate * learningRate * 0.90 - failureRate * learningRate * 0.55 + rewardBias * 0.30);
        double memoryRaw = memoryWeight * (1.0 + historyFactor * 0.50 + successRate * learningRate * 0.35 - failureRate * learningRate * 0.25 + rewardBias * 0.25);
        double latencyRaw = latencyWeight * (1.0 + latencySignal * learningRate * 0.85 + successRate * learningRate * 0.20 - failureRate * learningRate * 0.25 + Math.max(0.0, rewardBias) * 0.15);
        double sum = keywordRaw + successRaw + memoryRaw + latencyRaw;
        if (sum <= 0.0) {
            return new WeightProfile(keywordWeight, successWeight, memoryWeight, latencyWeight, List.of("neutral-weights"));
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("historyFactor=" + round(historyFactor));
        reasons.add("successRate=" + round(successRate));
        reasons.add("failureRate=" + round(failureRate));
        reasons.add("memorySignal=" + round(memorySignal));
        reasons.add("latencySignal=" + round(latencySignal));
        reasons.add("rewardSignal=" + round(rewardSignal));
        return new WeightProfile(
                clamp01(keywordRaw / sum),
                clamp01(successRaw / sum),
                clamp01(memoryRaw / sum),
                clamp01(latencyRaw / sum),
                reasons
        );
    }

    protected List<String> buildReasons(String skillName,
                                        String suggestedTarget,
                                        int rawKeywordScore,
                                        SkillUsageStats stats,
                                        double graphScore,
                                        double memorySignal,
                                        double successRate,
                                        double failureRate,
                                        double latencySignal,
                                        double averageLatencyMs,
                                        CostModel costModel,
                                        WeightProfile weights,
                                        PlannerLearningStore.LearningSnapshot learning) {
        List<String> reasons = new ArrayList<>();
        if (skillName.equals(suggestedTarget)) {
            reasons.add("explicit-target");
        }
        if (rawKeywordScore > 0) {
            reasons.add("keyword=" + rawKeywordScore);
        }
        if (stats != null && stats.totalCount() > 0) {
            reasons.add("successCount=" + stats.successCount());
            reasons.add("failureCount=" + stats.failureCount());
            reasons.add("successRate=" + round(successRate));
            reasons.add("failureRate=" + round(failureRate));
            reasons.add("memoryHits=" + stats.totalCount());
        }
        if (averageLatencyMs >= 0.0) {
            reasons.add("avgLatencyMs=" + round(averageLatencyMs));
        }
        if (graphScore > 0.0) {
            reasons.add("graphScore=" + round(graphScore));
        }
        reasons.add("memorySignal=" + round(memorySignal));
        reasons.add("latencySignal=" + round(latencySignal));
        if (learning != null && learning.sampleCount() > 0L) {
            reasons.add("plannerLearningScore=" + round(learning.score()));
            reasons.add("rewardScore=" + round(learning.rewardScore()));
            reasons.add("averageReward=" + round(learning.averageReward()));
            reasons.add("preferredRoute=" + learning.preferredRoute());
        }
        reasons.add("adaptiveWeights=keyword:" + round(weights.keywordWeight())
                + ",success:" + round(weights.successWeight())
                + ",memory:" + round(weights.memoryWeight())
                + ",latency:" + round(weights.latencyWeight()));
        reasons.add("capability=" + round(weights.keywordWeight() * clamp01(rawKeywordScore / 1000.0))
                + "/" + round(weights.successWeight() * successRate)
                + "/" + round(weights.memoryWeight() * memorySignal)
                + "/" + round(weights.latencyWeight() * latencySignal));
        if (costModel != null) {
            reasons.add("tokenCost=" + round(costModel.tokenCost()));
            reasons.add("cost=" + round(costModel.cost()));
        }
        return List.copyOf(reasons);
    }

    protected double normalizeKeywordScore(int rawKeywordScore) {
        if (rawKeywordScore <= 0) {
            return 0.0;
        }
        return Math.min(1.0, rawKeywordScore / 1000.0);
    }

    protected double normalizeMemoryScore(SkillUsageStats stats, long maxUsageCount) {
        if (stats == null || maxUsageCount <= 0) {
            return 0.0;
        }
        return Math.min(1.0, stats.totalCount() / (double) maxUsageCount);
    }

    protected double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    protected double inverseNormalized(double value, double scale) {
        if (scale <= 0.0) {
            return 0.5;
        }
        return clamp01(1.0 - Math.max(0.0, value) / scale);
    }

    protected double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    protected record WeightProfile(double keywordWeight,
                                   double successWeight,
                                   double memoryWeight,
                                   double latencyWeight,
                                   List<String> reasons) {

        protected WeightProfile {
            keywordWeight = normalize(keywordWeight);
            successWeight = normalize(successWeight);
            memoryWeight = normalize(memoryWeight);
            latencyWeight = normalize(latencyWeight);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        private static double normalize(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0.25;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    protected static final class CandidateAccumulator {
        private final String skillName;

        protected CandidateAccumulator(String skillName) {
            this.skillName = Optional.ofNullable(skillName).orElse("");
        }

        protected String skillName() {
            return skillName;
        }
    }
}
