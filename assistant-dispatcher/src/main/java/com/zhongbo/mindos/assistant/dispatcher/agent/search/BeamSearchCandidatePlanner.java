package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMatch;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class BeamSearchCandidatePlanner implements SearchPlanner {

    private static final double KEYWORD_WEIGHT = 0.40;
    private static final double SUCCESS_RATE_WEIGHT = 0.25;
    private static final double MEMORY_WEIGHT = 0.20;
    private static final double PATH_COST_WEIGHT = 0.15;

    private final SkillCatalogFacade skillEngine;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final ProcedureMemoryEngine procedureMemoryEngine;

    public BeamSearchCandidatePlanner(SkillCatalogFacade skillEngine,
                                      DispatcherMemoryFacade dispatcherMemoryFacade,
                                      ProcedureMemoryEngine procedureMemoryEngine) {
        this.skillEngine = skillEngine;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.procedureMemoryEngine = procedureMemoryEngine;
    }

    @Override
    public List<SearchCandidate> search(SearchPlanningRequest request) {
        if (request == null) {
            return List.of();
        }
        List<PlanNode> seedNodes = collectPlanNodes(request);
        if (seedNodes.isEmpty()) {
            return List.of();
        }
        List<PlanPath> beam = seedNodes.stream()
                .map(PlanPath::seed)
                .sorted((left, right) -> Double.compare(pathRank(right), pathRank(left)))
                .limit(request.beamWidth())
                .toList();
        for (int depth = 1; depth < request.maxDepth(); depth++) {
            List<PlanPath> nextBeam = new ArrayList<>(beam);
            for (PlanPath path : beam) {
                for (PlanNode nextNode : seedNodes) {
                    if (path.containsSkill(nextNode.skillName())) {
                        continue;
                    }
                    nextBeam.add(path.extend(withPathCost(nextNode, path.nodes().size() + 1)));
                }
            }
            nextBeam.sort((left, right) -> Double.compare(pathRank(right), pathRank(left)));
            beam = nextBeam.stream().limit(request.beamWidth()).toList();
        }
        return beam.stream()
                .map(SearchCandidate::from)
                .toList();
    }

    private List<PlanNode> collectPlanNodes(SearchPlanningRequest request) {
        Map<String, CandidateSignal> signals = new LinkedHashMap<>();
        if (!request.suggestedTarget().isBlank()) {
            signals.computeIfAbsent(request.suggestedTarget(), CandidateSignal::new).reasons.add("explicit-target");
            signals.get(request.suggestedTarget()).keywordScore = Math.max(signals.get(request.suggestedTarget()).keywordScore, 0.95);
        }
        if (skillEngine != null && !request.userInput().isBlank()) {
            skillEngine.detectSkillCandidates(request.userInput(), request.beamWidth() * 3).forEach(candidate ->
                    signals.computeIfAbsent(candidate.skillName(), CandidateSignal::new).mergeKeyword(normalize(candidate.score(), 1000.0), "keyword-score"));
        }
        if (!request.userId().isBlank()) {
            for (SkillUsageStats stats : dispatcherMemoryFacade.getSkillUsageStats(request.userId())) {
                CandidateSignal signal = signals.computeIfAbsent(stats.skillName(), CandidateSignal::new);
                signal.mergeSuccessRate(normalize(stats.successCount(), Math.max(1.0, stats.totalCount())), "success-rate");
                signal.mergeMemory(normalize(stats.totalCount(), maxUsageCount(request.userId())), "memory-usage");
            }
        }
        if (!request.userInput().isBlank()) {
            for (MemoryNode node : dispatcherMemoryFacade.searchGraphNodes(request.userId(), request.userInput(), request.beamWidth() * 2)) {
                String candidate = extractCandidateFromNode(node);
                if (!candidate.isBlank()) {
                    signals.computeIfAbsent(candidate, CandidateSignal::new).mergeMemory(0.72, "graph-memory");
                }
            }
        }
        if (procedureMemoryEngine != null) {
            for (ProcedureMatch match : procedureMemoryEngine.matchTemplates(request.userId(), request.userInput(), request.suggestedTarget(), request.beamWidth())) {
                match.template().steps().stream().map(step -> step.target()).filter(target -> !target.isBlank()).findFirst()
                        .ifPresent(target -> signals.computeIfAbsent(target, CandidateSignal::new).mergeMemory(Math.min(0.88, match.score()), "procedure-template"));
            }
        }
        List<PlanNode> planNodes = new ArrayList<>();
        for (CandidateSignal signal : signals.values()) {
            double baseScore = KEYWORD_WEIGHT * signal.keywordScore
                    + SUCCESS_RATE_WEIGHT * defaulted(signal.successRateScore, 0.50)
                    + MEMORY_WEIGHT * signal.memoryScore
                    - PATH_COST_WEIGHT * signal.pathCost;
            planNodes.add(new PlanNode(
                    signal.skillName,
                    round(signal.keywordScore),
                    round(defaulted(signal.successRateScore, 0.50)),
                    round(signal.memoryScore),
                    round(signal.pathCost),
                    round(baseScore),
                    List.copyOf(signal.reasons)
            ));
        }
        planNodes.sort((left, right) -> Double.compare(right.totalScore(), left.totalScore()));
        return List.copyOf(planNodes);
    }

    private PlanNode withPathCost(PlanNode node, int depth) {
        double pathCost = round((depth - 1) * 0.12);
        double totalScore = KEYWORD_WEIGHT * node.keywordScore()
                + SUCCESS_RATE_WEIGHT * node.successRateScore()
                + MEMORY_WEIGHT * node.memoryScore()
                - PATH_COST_WEIGHT * pathCost;
        List<String> reasons = new ArrayList<>(node.reasons());
        reasons.add("path-cost=" + pathCost);
        return new PlanNode(
                node.skillName(),
                node.keywordScore(),
                node.successRateScore(),
                node.memoryScore(),
                pathCost,
                round(totalScore),
                reasons
        );
    }

    private double pathRank(PlanPath path) {
        if (path == null) {
            return 0.0;
        }
        return round(path.score() - path.pathCost() * PATH_COST_WEIGHT);
    }

    private long maxUsageCount(String userId) {
        if (userId == null || userId.isBlank()) {
            return 1L;
        }
        return Math.max(1L, dispatcherMemoryFacade.getSkillUsageStats(userId).stream()
                .mapToLong(SkillUsageStats::totalCount)
                .max()
                .orElse(1L));
    }

    private double defaulted(double value, double fallback) {
        return value <= 0.0 ? fallback : value;
    }

    private String extractCandidateFromNode(MemoryNode node) {
        Object skillName = node.data().get("skillName");
        if (skillName != null && !String.valueOf(skillName).isBlank()) {
            return String.valueOf(skillName).trim();
        }
        String name = node.name();
        return name.contains(".") ? name.trim() : "";
    }

    private double normalize(double numerator, double denominator) {
        if (denominator <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, numerator / denominator);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class CandidateSignal {
        private final String skillName;
        private double keywordScore;
        private double successRateScore;
        private double memoryScore;
        private double pathCost;
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();

        private CandidateSignal(String skillName) {
            this.skillName = skillName == null ? "" : skillName.trim();
        }

        private void mergeKeyword(double score, String reason) {
            keywordScore = Math.max(keywordScore, score);
            reasons.add(reason);
        }

        private void mergeSuccessRate(double score, String reason) {
            successRateScore = Math.max(successRateScore, score);
            reasons.add(reason);
        }

        private void mergeMemory(double score, String reason) {
            memoryScore = Math.max(memoryScore, score);
            reasons.add(reason);
        }
    }
}
