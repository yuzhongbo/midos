package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionTargetResolver;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SignalFusionEngine {

    private static final int DEFAULT_TOP_K = 3;
    private static final int DEFAULT_DEBOUNCE_HOURS = 6;
    private static final double DEFAULT_HISTORY_SATURATION = 8.0;

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DecisionTargetResolver targetResolver = new DecisionTargetResolver();
    private final FusionWeights fusionWeights;
    private final int topK;
    private final Duration debounceWindow;
    private final double historySaturation;

    SignalFusionEngine() {
        this(null);
    }

    SignalFusionEngine(DispatcherMemoryFacade dispatcherMemoryFacade) {
        this(
                dispatcherMemoryFacade,
                FusionWeights.fromSystemProperties(),
                intProperty("mindos.dispatcher.planner2.top-k", DEFAULT_TOP_K),
                Duration.ofHours(intProperty("mindos.dispatcher.planner2.debounce-hours", DEFAULT_DEBOUNCE_HOURS)),
                doubleProperty("mindos.dispatcher.planner2.history-saturation", DEFAULT_HISTORY_SATURATION)
        );
    }

    SignalFusionEngine(DispatcherMemoryFacade dispatcherMemoryFacade,
                       FusionWeights fusionWeights,
                       int topK,
                       Duration debounceWindow,
                       double historySaturation) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.fusionWeights = fusionWeights == null ? FusionWeights.fromSystemProperties() : fusionWeights;
        this.topK = Math.max(1, topK);
        this.debounceWindow = debounceWindow == null || debounceWindow.isNegative() || debounceWindow.isZero()
                ? Duration.ofHours(DEFAULT_DEBOUNCE_HOURS)
                : debounceWindow;
        this.historySaturation = Math.max(1.0, historySaturation);
    }

    DecisionSelection fuse(List<DecisionSignal> signals) {
        return fuse(DecisionOrchestrator.UserInput.empty(), signals, Set.of());
    }

    DecisionSelection fuse(DecisionOrchestrator.UserInput input,
                           List<DecisionSignal> signals,
                           Set<String> excludedTargets) {
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        Set<String> excluded = canonicalizeTargets(excludedTargets);
        MemorySignals memorySignals = resolveMemorySignals(safeInput.userId(), safeInput.userInput());
        Map<String, CandidateAccumulator> grouped = new LinkedHashMap<>();
        List<DecisionSignal> retainedSignals = new ArrayList<>();
        if (signals != null) {
            for (DecisionSignal signal : signals) {
                if (signal == null) {
                    continue;
                }
                String canonicalTarget = targetResolver.canonicalize(signal.target());
                if (canonicalTarget.isBlank() || excluded.contains(canonicalTarget)) {
                    continue;
                }
                retainedSignals.add(new DecisionSignal(signal.target(), clamp01(signal.score()), signal.source()));
                grouped.computeIfAbsent(canonicalTarget, CandidateAccumulator::new).add(signal);
            }
        }
        List<CandidateScore> ranked = grouped.values().stream()
                .map(candidate -> candidate.score(memorySignals, fusionWeights, historySaturation))
                .sorted(Comparator
                        .comparingDouble(CandidateScore::finalScore).reversed()
                        .thenComparing(Comparator.comparingDouble(CandidateScore::baseScore).reversed())
                        .thenComparing(CandidateScore::target))
                .toList();
        List<CandidateScore> topCandidates = ranked.isEmpty()
                ? List.of()
                : List.copyOf(ranked.subList(0, Math.min(topK, ranked.size())));
        CandidateScore selected = topCandidates.isEmpty() ? null : topCandidates.get(0);
        return new DecisionSelection(
                selected == null ? "" : selected.target(),
                selected == null ? 0.0 : selected.finalScore(),
                selected == null ? "" : selected.primarySource(),
                selected == null ? "" : selected.intentHint(),
                topCandidates,
                retainedSignals.isEmpty() ? List.of() : List.copyOf(retainedSignals)
        );
    }

    private MemorySignals resolveMemorySignals(String userId, String userInput) {
        if (dispatcherMemoryFacade == null || userId == null || userId.isBlank()) {
            return MemorySignals.empty();
        }
        Map<String, SkillUsageStats> statsByTarget = new LinkedHashMap<>();
        for (SkillUsageStats stats : dispatcherMemoryFacade.getSkillUsageStats(userId)) {
            if (stats == null) {
                continue;
            }
            String canonicalTarget = targetResolver.canonicalize(stats.skillName());
            if (!canonicalTarget.isBlank()) {
                statsByTarget.put(canonicalTarget, stats);
            }
        }
        String normalizedInput = normalizeInput(userInput);
        ProceduralMemoryEntry recentExactMatch = dispatcherMemoryFacade.getSkillUsageHistory(userId).stream()
                .filter(entry -> entry != null && normalizeInput(entry.input()).equals(normalizedInput))
                .sorted(Comparator.comparing(ProceduralMemoryEntry::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(entry -> isWithinDebounceWindow(entry.createdAt()))
                .findFirst()
                .orElse(null);
        return new MemorySignals(
                statsByTarget.isEmpty() ? Map.of() : Map.copyOf(statsByTarget),
                recentExactMatch
        );
    }

    private boolean isWithinDebounceWindow(Instant createdAt) {
        if (createdAt == null) {
            return false;
        }
        return createdAt.plus(debounceWindow).isAfter(Instant.now());
    }

    private Set<String> canonicalizeTargets(Set<String> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawTarget : rawTargets) {
            String canonical = targetResolver.canonicalize(rawTarget);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static double doubleProperty(String key, double defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalizeSource(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeInput(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static final class FusionWeights {
        private final Map<String, Double> sourceWeights;
        private final double defaultSourceWeight;
        private final double successBoostWeight;
        private final double failurePenaltyWeight;
        private final double debounceBoost;
        private final double switchPenalty;

        private FusionWeights(Map<String, Double> sourceWeights,
                              double defaultSourceWeight,
                              double successBoostWeight,
                              double failurePenaltyWeight,
                              double debounceBoost,
                              double switchPenalty) {
            this.sourceWeights = sourceWeights == null ? Map.of() : Map.copyOf(sourceWeights);
            this.defaultSourceWeight = clamp01(defaultSourceWeight);
            this.successBoostWeight = clamp01(successBoostWeight);
            this.failurePenaltyWeight = clamp01(failurePenaltyWeight);
            this.debounceBoost = clamp01(debounceBoost);
            this.switchPenalty = clamp01(switchPenalty);
        }

        static FusionWeights fromSystemProperties() {
            Map<String, Double> weights = new LinkedHashMap<>();
            weights.put("explicit", doubleProperty("mindos.dispatcher.planner2.weight.explicit", 1.0));
            weights.put("semantic", doubleProperty("mindos.dispatcher.planner2.weight.semantic", 0.92));
            weights.put("llm", doubleProperty("mindos.dispatcher.planner2.weight.llm", 0.90));
            weights.put("rule", doubleProperty("mindos.dispatcher.planner2.weight.rule", 0.78));
            weights.put("rule-fallback", doubleProperty("mindos.dispatcher.planner2.weight.rule-fallback", 0.76));
            weights.put("memory", doubleProperty("mindos.dispatcher.planner2.weight.memory", 0.74));
            weights.put("heuristic", doubleProperty("mindos.dispatcher.planner2.weight.heuristic", 0.68));
            weights.put("profile", doubleProperty("mindos.dispatcher.planner2.weight.profile", 0.70));
            weights.put("multi-agent", doubleProperty("mindos.dispatcher.planner2.weight.multi-agent", 0.72));
            return new FusionWeights(
                    weights,
                    doubleProperty("mindos.dispatcher.planner2.weight.default", 0.60),
                    doubleProperty("mindos.dispatcher.planner2.success-boost", 0.08),
                    doubleProperty("mindos.dispatcher.planner2.failure-penalty", 0.10),
                    doubleProperty("mindos.dispatcher.planner2.debounce-boost", 0.08),
                    doubleProperty("mindos.dispatcher.planner2.switch-penalty", 0.05)
            );
        }

        double sourceWeight(String source) {
            return sourceWeights.getOrDefault(normalizeSource(source), defaultSourceWeight);
        }
    }

    record DecisionSelection(String selectedTarget,
                             double confidence,
                             String primarySource,
                             String intentHint,
                             List<CandidateScore> topCandidates,
                             List<DecisionSignal> retainedSignals) {

        boolean hasSelection() {
            return selectedTarget != null && !selectedTarget.isBlank();
        }
    }

    record CandidateScore(String target,
                          double finalScore,
                          double baseScore,
                          String primarySource,
                          String intentHint,
                          Map<String, Double> sourceScores,
                          double successBoost,
                          double failurePenalty,
                          double debounceAdjustment) {
    }

    private record MemorySignals(Map<String, SkillUsageStats> statsByTarget,
                                 ProceduralMemoryEntry recentExactMatch) {

        static MemorySignals empty() {
            return new MemorySignals(Map.of(), null);
        }
    }

    private final class CandidateAccumulator {
        private final String canonicalTarget;
        private final Map<String, Double> sourceScores = new LinkedHashMap<>();
        private String primarySource = "";
        private String intentHint = "";
        private double strongestContribution = -1.0;

        private CandidateAccumulator(String canonicalTarget) {
            this.canonicalTarget = canonicalTarget;
        }

        private void add(DecisionSignal signal) {
            String source = normalizeSource(signal.source());
            double score = clamp01(signal.score());
            sourceScores.merge(source, score, Math::max);
            double contribution = score * fusionWeights.sourceWeight(source);
            if (contribution > strongestContribution
                    || (Double.compare(contribution, strongestContribution) == 0
                    && fusionWeights.sourceWeight(source) >= fusionWeights.sourceWeight(primarySource))) {
                strongestContribution = contribution;
                primarySource = source;
                intentHint = signal.target();
            }
        }

        private CandidateScore score(MemorySignals memorySignals,
                                     FusionWeights weights,
                                     double saturationBase) {
            double weightedSum = 0.0;
            double totalWeight = 0.0;
            for (Map.Entry<String, Double> entry : sourceScores.entrySet()) {
                double weight = weights.sourceWeight(entry.getKey());
                weightedSum += entry.getValue() * weight;
                totalWeight += weight;
            }
            double baseScore = totalWeight <= 0.0 ? 0.0 : weightedSum / totalWeight;
            SkillUsageStats stats = memorySignals.statsByTarget().get(canonicalTarget);
            double successBoost = successBoost(stats, weights.successBoostWeight, saturationBase);
            double failurePenalty = failurePenalty(stats, weights.failurePenaltyWeight, saturationBase);
            double debounceAdjustment = debounceAdjustment(canonicalTarget, memorySignals.recentExactMatch(), weights);
            double finalScore = clamp01(baseScore + successBoost - failurePenalty + debounceAdjustment);
            return new CandidateScore(
                    canonicalTarget,
                    round(finalScore),
                    round(baseScore),
                    primarySource,
                    intentHint == null ? "" : intentHint,
                    sourceScores.isEmpty() ? Map.of() : Map.copyOf(sourceScores),
                    round(successBoost),
                    round(failurePenalty),
                    round(debounceAdjustment)
            );
        }

        private double successBoost(SkillUsageStats stats, double factor, double saturationBase) {
            if (!supportsHistoryTuning(canonicalTarget)) {
                return 0.0;
            }
            if (stats == null || stats.totalCount() <= 0) {
                return 0.0;
            }
            double saturation = Math.min(1.0, Math.log1p(stats.totalCount()) / Math.log1p(saturationBase));
            double successRate = stats.successCount() / (double) stats.totalCount();
            return factor * successRate * saturation;
        }

        private double failurePenalty(SkillUsageStats stats, double factor, double saturationBase) {
            if (!supportsHistoryTuning(canonicalTarget)) {
                return 0.0;
            }
            if (stats == null || stats.totalCount() <= 0) {
                return 0.0;
            }
            double saturation = Math.min(1.0, Math.log1p(stats.totalCount()) / Math.log1p(saturationBase));
            double failureRate = stats.failureCount() / (double) stats.totalCount();
            return factor * failureRate * saturation;
        }

        private double debounceAdjustment(String target,
                                          ProceduralMemoryEntry recentExactMatch,
                                          FusionWeights weights) {
            if (!supportsHistoryTuning(target)) {
                return 0.0;
            }
            if (recentExactMatch == null) {
                return 0.0;
            }
            String recentTarget = targetResolver.canonicalize(recentExactMatch.skillName());
            if (recentTarget.isBlank()) {
                return 0.0;
            }
            if (recentTarget.equals(target)) {
                return recentExactMatch.success() ? weights.debounceBoost : -weights.switchPenalty / 2.0;
            }
            return recentExactMatch.success() ? -weights.switchPenalty : 0.0;
        }

        private boolean supportsHistoryTuning(String target) {
            return target != null && !target.startsWith("mcp.");
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
