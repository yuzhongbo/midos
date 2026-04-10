package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMatch;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryNode;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryView;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BeamSearchCandidatePlanner implements SearchPlanner {

    private final SkillEngine skillEngine;
    private final MemoryGateway memoryGateway;
    private final GraphMemoryView graphMemoryView;
    private final ProcedureMemoryEngine procedureMemoryEngine;

    public BeamSearchCandidatePlanner(SkillEngine skillEngine,
                                      MemoryGateway memoryGateway,
                                      GraphMemoryView graphMemoryView,
                                      ProcedureMemoryEngine procedureMemoryEngine) {
        this.skillEngine = skillEngine;
        this.memoryGateway = memoryGateway;
        this.graphMemoryView = graphMemoryView;
        this.procedureMemoryEngine = procedureMemoryEngine;
    }

    @Override
    public List<SearchCandidate> search(SearchPlanningRequest request) {
        if (request == null) {
            return List.of();
        }
        List<Expansion> expansions = collectExpansions(request);
        if (expansions.isEmpty()) {
            return List.of();
        }
        List<SearchState> beam = expansions.stream()
                .map(expansion -> SearchState.seed(expansion.skillName, expansion.score, expansion.reason))
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .limit(request.beamWidth())
                .toList();
        for (int depth = 1; depth < request.maxDepth(); depth++) {
            List<SearchState> nextBeam = new ArrayList<>(beam);
            for (SearchState state : beam) {
                for (Expansion expansion : expansions) {
                    if (state.path.contains(expansion.skillName)) {
                        continue;
                    }
                    nextBeam.add(state.extend(expansion.skillName, expansion.score * 0.85, expansion.reason));
                }
            }
            nextBeam.sort((left, right) -> Double.compare(right.score, left.score));
            beam = nextBeam.stream().limit(request.beamWidth()).toList();
        }
        return beam.stream()
                .map(state -> new SearchCandidate(state.path, round(state.score), List.copyOf(state.reasons), Map.of("length", state.path.size())))
                .toList();
    }

    private List<Expansion> collectExpansions(SearchPlanningRequest request) {
        Map<String, Expansion> expansions = new LinkedHashMap<>();
        if (!request.suggestedTarget().isBlank()) {
            expansions.put(request.suggestedTarget(), new Expansion(request.suggestedTarget(), 0.95, "explicit-target"));
        }
        if (skillEngine != null && !request.userInput().isBlank()) {
            skillEngine.detectSkillCandidates(request.userInput(), request.beamWidth() * 3).forEach(candidate ->
                    expansions.putIfAbsent(candidate.skillName(), new Expansion(candidate.skillName(), normalize(candidate.score(), 1000.0), "keyword-score")));
        }
        if (memoryGateway != null && !request.userId().isBlank()) {
            for (SkillUsageStats stats : memoryGateway.skillUsageStats(request.userId())) {
                expansions.putIfAbsent(stats.skillName(), new Expansion(stats.skillName(), normalize(stats.successCount(), Math.max(1.0, stats.totalCount())), "procedural-prior"));
            }
        }
        if (graphMemoryView != null && !request.userInput().isBlank()) {
            for (GraphMemoryNode node : graphMemoryView.searchNodes(request.userId(), request.userInput(), request.beamWidth() * 2)) {
                String candidate = extractCandidateFromNode(node);
                if (!candidate.isBlank()) {
                    expansions.putIfAbsent(candidate, new Expansion(candidate, 0.72, "graph-memory"));
                }
            }
        }
        if (procedureMemoryEngine != null) {
            for (ProcedureMatch match : procedureMemoryEngine.matchTemplates(request.userId(), request.userInput(), request.suggestedTarget(), request.beamWidth())) {
                match.template().steps().stream().map(step -> step.target()).filter(target -> !target.isBlank()).findFirst()
                        .ifPresent(target -> expansions.putIfAbsent(target, new Expansion(target, Math.min(0.88, match.score()), "procedure-template")));
            }
        }
        return List.copyOf(expansions.values());
    }

    private String extractCandidateFromNode(GraphMemoryNode node) {
        Object skillName = node.attributes().get("skillName");
        if (skillName != null && !String.valueOf(skillName).isBlank()) {
            return String.valueOf(skillName).trim();
        }
        String name = node.name();
        return name != null && name.contains(".") ? name.trim() : "";
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

    private record Expansion(String skillName, double score, String reason) {
    }

    private static final class SearchState {
        private final List<String> path;
        private final double score;
        private final Set<String> reasons;

        private SearchState(List<String> path, double score, Set<String> reasons) {
            this.path = List.copyOf(path);
            this.score = score;
            this.reasons = Set.copyOf(reasons);
        }

        private static SearchState seed(String skillName, double score, String reason) {
            return new SearchState(List.of(skillName), score, Set.of(reason));
        }

        private SearchState extend(String skillName, double increment, String reason) {
            List<String> nextPath = new ArrayList<>(path);
            nextPath.add(skillName);
            Set<String> nextReasons = new LinkedHashSet<>(reasons);
            nextReasons.add(reason);
            return new SearchState(nextPath, score + increment, nextReasons);
        }
    }
}
