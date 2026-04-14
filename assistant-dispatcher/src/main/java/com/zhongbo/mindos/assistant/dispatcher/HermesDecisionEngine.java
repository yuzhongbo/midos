package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class HermesDecisionEngine {

    private static final double MIN_ROUTE_CONFIDENCE = 0.55d;

    private final SkillDslParser skillDslParser;
    private final SkillCatalogFacade skillCatalog;
    private final HermesToolSchemaCatalog toolSchemaCatalog;
    private final SemanticRoutingSupport semanticRoutingSupport;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final DecisionParamAssembler decisionParamAssembler;
    private final DispatchHeuristicsSupport heuristicsSupport;
    private final List<String> parallelSearchPriorityOrder;
    private final List<DispatchSkillDslResolver> dispatchSkillDslResolvers;

    HermesDecisionEngine(SkillDslParser skillDslParser,
                         SkillCatalogFacade skillCatalog,
                         HermesToolSchemaCatalog toolSchemaCatalog,
                         SemanticRoutingSupport semanticRoutingSupport,
                         BehaviorRoutingSupport behaviorRoutingSupport,
                         DecisionParamAssembler decisionParamAssembler,
                         DispatchHeuristicsSupport heuristicsSupport,
                         List<String> parallelSearchPriorityOrder,
                         List<DispatchSkillDslResolver> dispatchSkillDslResolvers) {
        this.skillDslParser = skillDslParser;
        this.skillCatalog = skillCatalog;
        this.toolSchemaCatalog = toolSchemaCatalog;
        this.semanticRoutingSupport = semanticRoutingSupport;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.decisionParamAssembler = decisionParamAssembler;
        this.heuristicsSupport = heuristicsSupport;
        this.parallelSearchPriorityOrder = parallelSearchPriorityOrder == null
                ? List.of()
                : parallelSearchPriorityOrder.stream()
                .map(value -> normalize(value).toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableList());
        this.dispatchSkillDslResolvers = dispatchSkillDslResolvers == null ? List.of() : List.copyOf(dispatchSkillDslResolvers);
    }

    DecisionPlan decide(HermesDecisionContext context) {
        HermesDecisionContext safeContext = context == null
                ? new HermesDecisionContext("", "", "", Map.of(), null, "", List.of(), List.of(), SemanticAnalysisResult.empty(), Map.of(), Map.of(), null)
                : context;
        SemanticAnalysisResult semanticAnalysis = safeContext.semanticAnalysis();

        Optional<SkillDsl> explicitDsl = skillDslParser == null
                ? Optional.empty()
                : skillDslParser.parse(safeContext.userInput());
        if (explicitDsl.isPresent()) {
            SkillDsl dsl = explicitDsl.get();
            Decision decision = new Decision("explicit", normalize(dsl.skill()), safeMap(dsl.input()), 1.0d, false);
            return new DecisionPlan(
                    decision,
                    "explicit-skill",
                    List.of("explicit skill DSL input"),
                    List.of(),
                    null
            );
        }

        Optional<DecisionPlan> explicitReservedSkill = directReservedSkillPlan(safeContext);
        if (explicitReservedSkill.isPresent()) {
            return explicitReservedSkill.get();
        }

        Optional<DecisionPlan> resolvedCommand = resolvedCommandPlan(safeContext);
        if (resolvedCommand.isPresent()) {
            return resolvedCommand.get();
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        addSemanticCandidates(candidates, safeContext);
        addDetectedCandidates(candidates, safeContext);
        addHabitCandidates(candidates, safeContext);
        boostCandidatesFromMemory(candidates, safeContext.skillSuccessRates());
        applySearchPriorityOverrides(candidates, safeContext);

        Candidate best = candidates.values().stream()
                .max(Comparator
                        .comparingDouble(Candidate::score)
                        .thenComparingInt(candidate -> searchPriorityTieBreaker(candidate.skillName()))
                        .thenComparingInt(candidate -> -routePrecedence(candidate.route()))
                        .thenComparing(Candidate::skillName))
                .orElse(null);

        if (best == null || best.score() < MIN_ROUTE_CONFIDENCE) {
            List<String> reasons = new ArrayList<>();
            reasons.add("no confident skill matched current input");
            if (!semanticAnalysis.summary().isBlank()) {
                reasons.add("semantic-summary=" + semanticAnalysis.summary());
            }
            return new DecisionPlan(
                    new Decision(resolveIntent(semanticAnalysis), "llm", Map.of(), semanticAnalysis.effectiveConfidence(), false),
                    "llm-fallback",
                    List.copyOf(reasons),
                    List.of("no explicit SkillDSL", "no deterministic rule matched current input"),
                    null
            );
        }

        Decision decision = new Decision(
                resolveIntent(best, semanticAnalysis),
                best.skillName(),
                safeMap(best.params()),
                clamp(best.score()),
                best.needClarify()
        );
        return new DecisionPlan(decision, best.route(), List.copyOf(best.reasons()), List.of(), best.clarifyReply());
    }

    private Optional<DecisionPlan> directReservedSkillPlan(HermesDecisionContext context) {
        if (context == null || skillCatalog == null || context.routingInput().isBlank()) {
            return Optional.empty();
        }
        for (SkillCandidate candidate : skillCatalog.detectSkillCandidates(context.routingInput(), 3)) {
            if (candidate == null || !isExplicitReservedSkill(context.userInput(), candidate.skillName())) {
                continue;
            }
            Map<String, Object> params = decisionParamAssembler == null
                    ? Map.of()
                    : decisionParamAssembler.decisionParamsFromInput(
                    candidate.skillName(),
                    context.userInput(),
                    context.skillContext() == null ? Map.of() : context.skillContext().attributes()
            );
            Decision decision = new Decision(
                    candidate.skillName(),
                    candidate.skillName(),
                    safeMap(params),
                    0.98d,
                    false
            );
            return Optional.of(new DecisionPlan(
                    decision,
                    "detected-skill",
                    List.of("explicit reserved diagnostic skill requested"),
                    List.of(),
                    null
            ));
        }
        return Optional.empty();
    }

    private Optional<DecisionPlan> resolvedCommandPlan(HermesDecisionContext context) {
        if (context == null || dispatchSkillDslResolvers.isEmpty()) {
            return Optional.empty();
        }
        for (DispatchSkillDslResolver resolver : dispatchSkillDslResolvers) {
            if (resolver == null) {
                continue;
            }
            Optional<SkillDsl> resolved = resolver.resolve(context.userId(), context.userInput(), context.profileContext());
            if (resolved.isEmpty()) {
                continue;
            }
            SkillDsl dsl = resolved.get();
            Decision decision = new Decision(
                    normalize(dsl.skill()),
                    normalize(dsl.skill()),
                    safeMap(dsl.input()),
                    0.99d,
                    false
            );
            return Optional.of(new DecisionPlan(
                    decision,
                    "resolver-command",
                    List.of("dispatcher command resolver matched a deterministic input pattern"),
                    List.of(),
                    null
            ));
        }
        return Optional.empty();
    }

    private void addSemanticCandidates(Map<String, Candidate> candidates, HermesDecisionContext context) {
        SemanticAnalysisResult semanticAnalysis = context == null ? SemanticAnalysisResult.empty() : context.semanticAnalysis();
        if (semanticAnalysis == null) {
            return;
        }
        if (semanticRoutingSupport != null && context != null) {
            for (SemanticRoutingSupport.SemanticRoutingPlan plan : semanticRoutingSupport.recommendSemanticRoutingPlans(
                    context.userId(),
                    semanticAnalysis,
                    context.userInput()
            )) {
                if (plan == null || plan.skillName().isBlank()) {
                    continue;
                }
                List<String> reasons = new ArrayList<>();
                reasons.add("semantic candidate intent matched a registered tool");
                if (normalize(plan.skillName()).equals(normalize(semanticAnalysis.suggestedSkill()))) {
                    reasons.add("semantic analyzer suggested a concrete skill");
                }
                if (isRealtimeIntent(context.userInput(), semanticAnalysis) || isRealtimeSearchSkill(plan.skillName())) {
                    reasons.add("realtime semantic route");
                }
                boolean needClarify = semanticRoutingSupport.shouldAskSemanticClarification(
                        semanticAnalysis,
                        context.userInput(),
                        plan
                );
                addCandidate(
                        candidates,
                        plan.skillName(),
                        plan.confidence(),
                        "semantic-analysis",
                        reasons,
                        plan.effectivePayload(),
                        needClarify,
                        needClarify ? semanticRoutingSupport.buildSemanticClarifyReply(semanticAnalysis, plan) : null,
                        false
                );
            }
        }
        addCandidate(
                candidates,
                semanticAnalysis.suggestedSkill(),
                semanticAnalysis.effectiveConfidence(),
                "semantic-analysis",
                List.of("semantic analyzer suggested a concrete skill"),
                buildSemanticFallbackParams(context, semanticAnalysis, semanticAnalysis.suggestedSkill()),
                false,
                null,
                false
        );
        addCandidate(
                candidates,
                semanticAnalysis.intent(),
                semanticAnalysis.effectiveConfidence() - 0.03d,
                "semantic-analysis",
                List.of("semantic intent matched a registered tool"),
                buildSemanticFallbackParams(context, semanticAnalysis, semanticAnalysis.intent()),
                false,
                null,
                false
        );
        for (SemanticAnalysisResult.CandidateIntent candidateIntent : semanticAnalysis.candidateIntents()) {
            if (candidateIntent == null) {
                continue;
            }
            addCandidate(
                    candidates,
                    candidateIntent.intent(),
                    candidateIntent.confidence() - 0.02d,
                    "semantic-analysis",
                    List.of("semantic candidate intent matched a registered tool"),
                    buildSemanticFallbackParams(context, semanticAnalysis, candidateIntent.intent()),
                    false,
                    null,
                    false
            );
        }
    }

    private void addDetectedCandidates(Map<String, Candidate> candidates, HermesDecisionContext context) {
        String routingInput = context == null ? "" : context.routingInput();
        if (skillCatalog == null || routingInput == null || routingInput.isBlank()) {
            return;
        }
        List<SkillCandidate> detected = skillCatalog.detectSkillCandidates(routingInput, 5);
        String route = detected.size() > 1 ? "detected-skill-parallel" : "detected-skill";
        boolean hasMcpSearchCandidate = detected.stream()
                .filter(candidate -> candidate != null)
                .anyMatch(candidate -> isRealtimeSearchSkill(candidate.skillName()));
        for (SkillCandidate candidate : detected) {
            if (candidate == null) {
                continue;
            }
            addCandidate(
                    candidates,
                    candidate.skillName(),
                    detectedCandidateScore(context, candidate, hasMcpSearchCandidate),
                    route,
                    detectedReasons(context, candidate),
                    buildDetectedParams(context, candidate.skillName()),
                    false,
                    null,
                    false
            );
        }
    }

    private void addHabitCandidates(Map<String, Candidate> candidates, HermesDecisionContext context) {
        if (behaviorRoutingSupport == null || context == null || context.userId().isBlank() || context.userInput().isBlank()) {
            return;
        }
        if (heuristicsSupport != null && heuristicsSupport.isRealtimeLikeInput(context.userInput(), context.semanticAnalysis())) {
            return;
        }
        List<DecisionSignal> recommendations = behaviorRoutingSupport.recommendSkillsWithMemoryHabits(
                context.userId(),
                context.userInput(),
                context.profileContext(),
                skill -> false
        );
        for (DecisionSignal recommendation : recommendations) {
            if (recommendation == null || recommendation.target().isBlank()) {
                continue;
            }
            Optional<SkillDsl> habitDsl = behaviorRoutingSupport.buildHabitSkillDsl(
                    context.userId(),
                    context.userInput(),
                    context.profileContext(),
                    recommendation
            );
            if (habitDsl.isEmpty()) {
                continue;
            }
            addCandidate(
                    candidates,
                    recommendation.target(),
                    recommendation.score(),
                    "memory-habit",
                    List.of("recent successful skill history matched continuation intent"),
                    safeMap(habitDsl.get().input()),
                    false,
                    null,
                    false
            );
        }
    }

    private void boostCandidatesFromMemory(Map<String, Candidate> candidates, Map<String, Double> successRates) {
        if (candidates.isEmpty() || successRates == null || successRates.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Candidate> entry : new ArrayList<>(candidates.entrySet())) {
            Double successRate = successRates.get(entry.getKey());
            if (successRate == null) {
                continue;
            }
            double memoryBoost = Math.max(0.0d, successRate - 0.50d) * 0.20d;
            Candidate current = entry.getValue();
            List<String> reasons = new ArrayList<>(current.reasons());
            reasons.add("memory-success=" + String.format(Locale.ROOT, "%.2f", successRate));
            candidates.put(entry.getKey(), new Candidate(
                    current.skillName(),
                    clamp(current.score() + memoryBoost),
                    current.route(),
                    current.params(),
                    List.copyOf(reasons),
                    current.needClarify(),
                    current.clarifyReply()
            ));
        }
    }

    private void applySearchPriorityOverrides(Map<String, Candidate> candidates, HermesDecisionContext context) {
        if (candidates.isEmpty()
                || context == null
                || parallelSearchPriorityOrder.isEmpty()
                || !isRealtimeIntent(context.userInput(), context.semanticAnalysis())) {
            return;
        }
        Candidate preferred = null;
        String preferredKey = null;
        int preferredRank = Integer.MAX_VALUE;
        double topSearchScore = 0.0d;
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            Candidate candidate = entry.getValue();
            if (candidate == null || !isRealtimeSearchSkill(candidate.skillName())) {
                continue;
            }
            topSearchScore = Math.max(topSearchScore, candidate.score());
            int candidateRank = priorityRank(candidate.skillName());
            if (candidateRank < preferredRank) {
                preferred = candidate;
                preferredKey = entry.getKey();
                preferredRank = candidateRank;
            }
        }
        if (preferred == null || preferredKey == null) {
            return;
        }
        List<String> reasons = new ArrayList<>(preferred.reasons());
        reasons.add("search-priority=" + normalize(preferred.skillName()).toLowerCase(Locale.ROOT));
        candidates.put(preferredKey, new Candidate(
                preferred.skillName(),
                clamp(Math.max(preferred.score(), topSearchScore + 0.02d)),
                preferred.route(),
                preferred.params(),
                List.copyOf(reasons),
                preferred.needClarify(),
                preferred.clarifyReply()
        ));
    }

    private void addCandidate(Map<String, Candidate> candidates,
                              String skillName,
                              double score,
                              String route,
                              List<String> reasons,
                              Map<String, Object> params,
                              boolean needClarify,
                              String clarifyReply,
                              boolean allowReservedTarget) {
        String normalizedSkill = normalize(skillName);
        if (normalizedSkill.isBlank()
                || (!allowReservedTarget && !toolSchemaCatalog.isDecisionEligible(normalizedSkill))
                || !isKnownSkill(normalizedSkill)) {
            return;
        }
        Candidate incoming = new Candidate(
                normalizedSkill,
                clamp(score),
                route == null || route.isBlank() ? "decision-engine" : route,
                safeMap(params),
                reasons == null ? List.of() : List.copyOf(reasons),
                needClarify,
                clarifyReply
        );
        Candidate existing = candidates.get(normalizedSkill);
        if (existing == null || incoming.score() > existing.score()) {
            candidates.put(normalizedSkill, incoming);
            return;
        }
        if (incoming.score() == existing.score() && routePrecedence(incoming.route()) < routePrecedence(existing.route())) {
            candidates.put(normalizedSkill, incoming);
        }
    }

    private String resolveIntent(Candidate best, SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis != null && !semanticAnalysis.intent().isBlank()) {
            return semanticAnalysis.intent();
        }
        return best == null ? "" : best.skillName();
    }

    private String resolveIntent(SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null) {
            return "";
        }
        if (!semanticAnalysis.intent().isBlank()) {
            return semanticAnalysis.intent();
        }
        return semanticAnalysis.suggestedSkill();
    }

    private int routePrecedence(String route) {
        if ("semantic-analysis".equals(route)) {
            return 0;
        }
        if ("memory-habit".equals(route)) {
            return 1;
        }
        if ("detected-skill".equals(route) || "detected-skill-parallel".equals(route)) {
            return 2;
        }
        if ("explicit-skill".equals(route)) {
            return -1;
        }
        return 3;
    }

    private double detectedCandidateScore(HermesDecisionContext context,
                                          SkillCandidate candidate,
                                          boolean hasMcpSearchCandidate) {
        if (candidate == null) {
            return 0.92d;
        }
        int priorityRank = priorityRank(candidate.skillName());
        double priorityBoost = priorityRank == Integer.MAX_VALUE
                ? 0.0d
                : Math.max(0, parallelSearchPriorityOrder.size() - priorityRank) * 0.01d;
        double score = 0.92d + priorityBoost + Math.min(Math.max(candidate.score(), 0), 5) * 0.001d;
        String normalizedInput = context == null ? "" : normalize(context.userInput()).toLowerCase(Locale.ROOT);
        if ("mcp.docs.searchDocs".equals(candidate.skillName())
                && (normalizedInput.contains("searchdocs") || normalizedInput.contains("docs"))) {
            score += 0.08d;
        }
        if (isRealtimeIntent(context == null ? "" : context.userInput(), context == null ? SemanticAnalysisResult.empty() : context.semanticAnalysis())) {
            if (isRealtimeSearchSkill(candidate.skillName())) {
                score += 0.05d;
            }
            if (hasMcpSearchCandidate && "news_search".equals(normalize(candidate.skillName()))) {
                score -= 0.08d;
            }
        }
        return score;
    }

    private int priorityRank(String skillName) {
        String normalized = normalize(skillName).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Integer.MAX_VALUE;
        }
        for (int index = 0; index < parallelSearchPriorityOrder.size(); index++) {
            if (normalized.equals(parallelSearchPriorityOrder.get(index))) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int searchPriorityTieBreaker(String skillName) {
        int rank = priorityRank(skillName);
        if (rank == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, parallelSearchPriorityOrder.size() - rank);
    }

    private Map<String, Object> buildSemanticFallbackParams(HermesDecisionContext context,
                                                            SemanticAnalysisResult semanticAnalysis,
                                                            String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Map.of();
        }
        if (context == null) {
            return safeMap(semanticAnalysis == null ? Map.of() : semanticAnalysis.payload());
        }
        if (semanticRoutingSupport != null) {
            Map<String, Object> completed = semanticRoutingSupport.completeSemanticPayload(
                    context.userId(),
                    semanticAnalysis,
                    context.userInput(),
                    skillName
            );
            if (!completed.isEmpty()) {
                return safeMap(completed);
            }
        }
        if (decisionParamAssembler == null) {
            return safeMap(semanticAnalysis == null ? Map.of() : semanticAnalysis.payload());
        }
        return decisionParamAssembler.decisionParamsFromInput(
                skillName,
                context.userInput(),
                context.skillContext() == null ? Map.of() : context.skillContext().attributes()
        );
    }

    private Map<String, Object> buildDetectedParams(HermesDecisionContext context, String skillName) {
        if (context == null || decisionParamAssembler == null) {
            return Map.of();
        }
        return decisionParamAssembler.assembleParams(skillName, "detected", context.userInput(), context.skillContext());
    }

    private List<String> detectedReasons(HermesDecisionContext context, SkillCandidate candidate) {
        List<String> reasons = new ArrayList<>();
        reasons.add("registered routing keywords matched the input");
        if (context != null && candidate != null && (isRealtimeIntent(context.userInput(), context.semanticAnalysis())
                || isRealtimeSearchSkill(candidate.skillName()))) {
            reasons.add("realtime detected route");
        }
        return List.copyOf(reasons);
    }

    private boolean isExplicitReservedSkill(String userInput, String skillName) {
        String normalizedSkill = normalize(skillName).toLowerCase(Locale.ROOT);
        String normalizedInput = normalize(userInput).toLowerCase(Locale.ROOT);
        if ("semantic.analyze".equals(normalizedSkill)) {
            return normalizedInput.startsWith("semantic") || normalizedInput.contains(" semantic ");
        }
        return false;
    }

    private boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return heuristicsSupport != null && heuristicsSupport.isRealtimeIntent(userInput, semanticAnalysis);
    }

    private boolean isRealtimeSearchSkill(String skillName) {
        String normalized = normalize(skillName).toLowerCase(Locale.ROOT);
        return normalized.startsWith("mcp.") && normalized.contains("search");
    }

    private boolean isKnownSkill(String skillName) {
        if (skillName == null || skillName.isBlank() || skillCatalog == null) {
            return false;
        }
        String normalized = normalize(skillName);
        return skillCatalog.listSkillDescriptors().stream()
                .filter(descriptor -> descriptor != null && descriptor.name() != null)
                .map(descriptor -> normalize(descriptor.name()))
                .anyMatch(normalized::equals)
                || skillCatalog.listAvailableSkillSummaries().stream()
                .map(summary -> {
                    int separator = summary == null ? -1 : summary.indexOf(" - ");
                    return separator >= 0 ? normalize(summary.substring(0, separator)) : normalize(summary);
                })
                .anyMatch(normalized::equals);
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of() : Map.copyOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    record DecisionPlan(Decision decision,
                        String route,
                        List<String> reasons,
                        List<String> rejectedReasons,
                        String clarifyReply) {
        DecisionPlan {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            rejectedReasons = rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons);
        }
    }

    private record Candidate(String skillName,
                             double score,
                             String route,
                             Map<String, Object> params,
                             List<String> reasons,
                             boolean needClarify,
                             String clarifyReply) {
    }
}
