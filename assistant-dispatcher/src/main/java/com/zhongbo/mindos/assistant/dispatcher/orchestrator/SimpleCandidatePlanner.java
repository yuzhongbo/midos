package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SimpleCandidatePlanner implements CandidatePlanner {

    private final SkillEngine skillEngine;
    private final MemoryGateway memoryGateway;
    private final GraphMemory graphMemory;
    private final SkillCostTelemetry skillCostTelemetry;
    private final int maxCandidates;
    private final double explicitWeight;
    private final double keywordWeight;
    private final double memoryWeight;

    public SimpleCandidatePlanner() {
        this(null, null, null, (SkillCostTelemetry) null, 3, 0.40, 0.35, 0.15, 0.10);
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        this(skillEngine, memoryGateway, null, (SkillCostTelemetry) null, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        this(skillEngine, memoryGateway, graphMemory, (SkillCostTelemetry) null, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  SkillCostTelemetry skillCostTelemetry,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        this.skillEngine = skillEngine;
        this.memoryGateway = memoryGateway;
        this.graphMemory = graphMemory;
        this.skillCostTelemetry = skillCostTelemetry;
        this.maxCandidates = Math.max(1, Math.min(3, maxCandidates));
        this.explicitWeight = explicitWeight;
        this.keywordWeight = keywordWeight;
        this.memoryWeight = memoryWeight;
    }

    @Autowired
    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  ObjectProvider<SkillCostTelemetry> skillCostTelemetryProvider,
                                  @Value("${mindos.dispatcher.candidate-planner.max-candidates:3}") int maxCandidates,
                                  @Value("${mindos.dispatcher.candidate-planner.explicit-weight:0.40}") double explicitWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.keyword-weight:0.35}") double keywordWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.memory-weight:0.15}") double memoryWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.success-weight:0.10}") double successWeight) {
        this(skillEngine, memoryGateway, graphMemory,
                skillCostTelemetryProvider == null ? null : skillCostTelemetryProvider.getIfAvailable(),
                maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    @Override
    public List<ScoredCandidate> plan(String suggestedTarget, DecisionOrchestrator.OrchestrationRequest request) {
        Map<String, Integer> keywordScores = detectKeywordScores(request == null ? "" : request.userInput());
        Map<String, SkillUsageStats> usageStats = lookupUsageStats(request == null ? "" : request.userId());
        Map<String, CostModel> costModels = lookupCostModels(request == null ? "" : request.userId());
        Map<String, Double> graphScores = lookupGraphScores(request == null ? "" : request.userId(),
                request == null ? "" : request.userInput(),
                suggestedTarget,
                keywordScores.keySet());
        Map<String, CandidateAccumulator> candidates = new LinkedHashMap<>();
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            candidates.put(suggestedTarget, new CandidateAccumulator(suggestedTarget));
        }
        keywordScores.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        graphScores.keySet().forEach(name -> candidates.computeIfAbsent(name, CandidateAccumulator::new));
        if (candidates.isEmpty()) {
            return List.of();
        }
        long maxUsageCount = usageStats.values().stream()
                .mapToLong(SkillUsageStats::totalCount)
                .max()
                .orElse(0L);
        List<ScoredCandidate> scored = new ArrayList<>();
        for (CandidateAccumulator accumulator : candidates.values()) {
            int rawKeywordScore = keywordScores.getOrDefault(accumulator.skillName, accumulator.skillName.equals(suggestedTarget) ? 700 : 0);
            double normalizedKeyword = normalizeKeywordScore(rawKeywordScore);
            SkillUsageStats stats = usageStats.get(accumulator.skillName);
            double graphScore = graphScores.getOrDefault(accumulator.skillName, 0.0);
            double memoryScore = Math.min(1.0, normalizeMemoryScore(stats, maxUsageCount) + graphScore);
            CostModel costModel = costModels.get(accumulator.skillName);
            double successRate = costModel == null ? normalizeSuccessRate(stats) : costModel.successRate();
            double cost = costModel == null ? 0.5 : costModel.cost();
            double explicitScore = accumulator.skillName.equals(suggestedTarget) ? 1.0 : 0.0;
            double capability = clamp01(explicitWeight * explicitScore
                    + keywordWeight * normalizedKeyword
                    + memoryWeight * memoryScore);
            double finalScore = 0.4 * capability
                    + 0.3 * successRate
                    + 0.3 * (1.0 - cost);
            List<String> reasons = buildReasons(accumulator.skillName,
                    suggestedTarget,
                    rawKeywordScore,
                    stats,
                    graphMemory != null,
                    graphScore,
                    memoryScore,
                    capability,
                    successRate,
                    cost,
                    costModel);
            scored.add(new ScoredCandidate(accumulator.skillName, round(finalScore), round(normalizedKeyword), round(memoryScore), round(successRate), reasons));
        }
        scored.sort(Comparator
                .comparingDouble(ScoredCandidate::finalScore).reversed()
                .thenComparing(ScoredCandidate::skillName));
        int safeLimit = Math.min(maxCandidates, scored.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(scored.subList(0, safeLimit));
    }

    private Map<String, Integer> detectKeywordScores(String input) {
        if (skillEngine == null || input == null || input.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        skillEngine.detectSkillCandidates(input, 8).forEach(candidate ->
                scores.put(candidate.skillName(), candidate.score()));
        return Map.copyOf(scores);
    }

    private Map<String, SkillUsageStats> lookupUsageStats(String userId) {
        if (memoryGateway == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, SkillUsageStats> stats = new LinkedHashMap<>();
        memoryGateway.skillUsageStats(userId).forEach(entry -> stats.put(entry.skillName(), entry));
        return Map.copyOf(stats);
    }

    private Map<String, CostModel> lookupCostModels(String userId) {
        if (skillCostTelemetry == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        Map<String, CostModel> models = new LinkedHashMap<>();
        skillCostTelemetry.costModels(userId).forEach(models::put);
        return Map.copyOf(models);
    }

    private Map<String, Double> lookupGraphScores(String userId,
                                                  String userInput,
                                                  String suggestedTarget,
                                                  java.util.Set<String> keywordCandidates) {
        if (graphMemory == null || userId == null || userId.isBlank()) {
            return Map.of();
        }
        List<String> candidateNames = new ArrayList<>(keywordCandidates == null ? List.of() : keywordCandidates);
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            candidateNames.add(suggestedTarget);
        }
        return graphMemory.scoreCandidates(userId, userInput, candidateNames);
    }

    private double normalizeKeywordScore(int rawKeywordScore) {
        if (rawKeywordScore <= 0) {
            return 0.0;
        }
        return Math.min(1.0, rawKeywordScore / 1000.0);
    }

    private double normalizeMemoryScore(SkillUsageStats stats, long maxUsageCount) {
        if (stats == null || maxUsageCount <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) stats.totalCount() / (double) maxUsageCount);
    }

    private double normalizeSuccessRate(SkillUsageStats stats) {
        if (stats == null || stats.totalCount() <= 0) {
            return 0.50;
        }
        return Math.min(1.0, (double) stats.successCount() / (double) stats.totalCount());
    }

    private List<String> buildReasons(String skillName,
                                      String suggestedTarget,
                                      int rawKeywordScore,
                                      SkillUsageStats stats,
                                      boolean graphEnabled,
                                      double graphScore,
                                      double memoryScore,
                                      double capability,
                                      double successRate,
                                      double cost,
                                      CostModel costModel) {
        List<String> reasons = new ArrayList<>();
        if (skillName.equals(suggestedTarget)) {
            reasons.add("explicit-target");
        }
        if (rawKeywordScore > 0) {
            reasons.add("keyword=" + rawKeywordScore);
        }
        if (stats != null && stats.totalCount() > 0) {
            reasons.add("memoryHits=" + stats.totalCount());
            reasons.add("successRate=" + round(successRate));
            if (memoryScore > 0.0) {
                reasons.add("habitScore=" + round(memoryScore));
            }
        }
        if (graphEnabled) {
            reasons.add("graphScore=" + round(graphScore));
        }
        reasons.add("capability=" + round(capability));
        if (costModel != null) {
            reasons.add("tokenCost=" + round(costModel.tokenCost()));
            reasons.add("latency=" + round(costModel.latency()));
            reasons.add("cost=" + round(cost));
        } else {
            reasons.add("cost=default:" + round(cost));
        }
        if (reasons.isEmpty()) {
            reasons.add("fallback-candidate");
        }
        return List.copyOf(reasons);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class CandidateAccumulator {
        private final String skillName;

        private CandidateAccumulator(String skillName) {
            this.skillName = Optional.ofNullable(skillName).orElse("");
        }
    }
}
