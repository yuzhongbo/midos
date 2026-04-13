package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionPlanner;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class SkillRoutingSupport {

    private static final Pattern ROUTING_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}.#_-]+");

    private final SkillCatalogFacade skillEngine;
    private final DecisionPlanner decisionPlanner;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final int shortlistMaxSkills;
    private final Function<String, String> memoryBucketResolver;

    SkillRoutingSupport(SkillCatalogFacade skillEngine,
                        DecisionPlanner decisionPlanner,
                        DispatcherMemoryFacade dispatcherMemoryFacade,
                        BehaviorRoutingSupport behaviorRoutingSupport,
                        int shortlistMaxSkills,
                        Function<String, String> memoryBucketResolver) {
        this.skillEngine = skillEngine;
        this.decisionPlanner = decisionPlanner;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.shortlistMaxSkills = Math.max(1, shortlistMaxSkills);
        this.memoryBucketResolver = memoryBucketResolver;
    }

    List<Candidate> recommend(RecommendationInput input) {
        return rankSkillRoutingCandidates(
                input == null ? "" : input.userId(),
                input == null ? "" : input.userInput()
        ).stream()
                .map(SkillRoutingRecommendation::candidate)
                .toList();
    }

    String describeSkillRoutingCandidates(String userId, String userInput) {
        List<SkillRoutingRecommendation> rankedCandidates = rankSkillRoutingCandidates(userId, userInput);
        if (rankedCandidates.isEmpty()) {
            return "";
        }

        List<String> shortlisted = rankedCandidates.stream()
                .filter(candidate -> candidate.candidate().score() > 0)
                .limit(shortlistMaxSkills)
                .map(SkillRoutingRecommendation::summary)
                .toList();
        if (shortlisted.isEmpty()) {
            shortlisted = rankedCandidates.stream()
                    .limit(shortlistMaxSkills)
                    .map(SkillRoutingRecommendation::summary)
                    .toList();
        }

        return shortlisted.stream()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    int bestSkillRoutingScore(String userId, String userInput) {
        return recommend(new RecommendationInput(userId, userInput)).stream()
                .mapToInt(candidate -> (int) Math.round(candidate.score()))
                .max()
                .orElse(0);
    }

    private List<SkillRoutingRecommendation> rankSkillRoutingCandidates(String userId, String userInput) {
        List<String> summaries = skillEngine.listAvailableSkillSummaries();
        if (summaries.isEmpty()) {
            return List.of();
        }
        Set<String> inputTokens = routingTokens(userInput);
        String plannedTarget = resolvePlannedTarget(userId, userInput);
        Optional<String> preferredFromStats = behaviorRoutingSupport.preferredSkillFromStats(userId);
        Optional<String> preferredFromHistory = behaviorRoutingSupport.preferredSkillFromHistory(
                dispatcherMemoryFacade.getSkillUsageHistory(userId)
        );
        String normalizedInput = normalize(userInput);

        return summaries.stream()
                .map(summary -> new SkillRoutingRecommendation(
                        summary,
                        toCandidate(summary, skillRoutingScore(
                                summary,
                                normalizedInput,
                                inputTokens,
                                plannedTarget,
                                preferredFromStats,
                                preferredFromHistory
                        ))
                ))
                .sorted(Comparator.comparingDouble((SkillRoutingRecommendation recommendation) -> recommendation.candidate().score()).reversed()
                        .thenComparing(SkillRoutingRecommendation::summary))
                .toList();
    }

    private int skillRoutingScore(String summary,
                                  String normalizedInput,
                                  Set<String> inputTokens,
                                  String plannedTarget,
                                  Optional<String> preferredFromStats,
                                  Optional<String> preferredFromHistory) {
        String skillName = summary;
        String description = "";
        int separator = summary.indexOf(" - ");
        if (separator >= 0) {
            skillName = summary.substring(0, separator).trim();
            description = summary.substring(separator + 3).trim();
        }
        String normalizedSkillName = normalize(skillName);
        int score = 0;
        if (!normalizedSkillName.isBlank() && normalizedInput.contains(normalizedSkillName)) {
            score += 80;
        }
        if (!plannedTarget.isBlank() && plannedTarget.equals(normalizedSkillName)) {
            score += 80;
        }
        Set<String> skillTokens = routingTokens(skillName + " " + description);
        for (String token : inputTokens) {
            if (skillTokens.contains(token)) {
                score += 12;
            }
        }
        if (preferredFromStats.filter(normalizedSkillName::equals).isPresent()) {
            score += 30;
        }
        if (preferredFromHistory.filter(normalizedSkillName::equals).isPresent()) {
            score += 20;
        }
        return score;
    }

    private Set<String> routingTokens(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String[] parts = ROUTING_TOKEN_SPLIT_PATTERN.split(normalized, -1);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.length() == 1 && !containsHan(part)) {
                continue;
            }
            tokens.add(part);
        }
        return tokens.isEmpty() ? Set.of() : Set.copyOf(tokens);
    }

    private boolean containsHan(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String resolvePlannedTarget(String userId, String userInput) {
        if (decisionPlanner == null) {
            return "";
        }
        String routingIntent = "";
        if (memoryBucketResolver != null) {
            String resolved = memoryBucketResolver.apply(userInput);
            routingIntent = resolved == null ? "" : resolved.trim();
        }
        return normalize(decisionPlanner.plan(
                userInput,
                routingIntent,
                Map.of(),
                new SkillContext(userId == null ? "" : userId, userInput == null ? "" : userInput, Map.of())
        ).target());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private Candidate toCandidate(String summary, int score) {
        String skillName = summary;
        int separator = summary.indexOf(" - ");
        if (separator >= 0) {
            skillName = summary.substring(0, separator).trim();
        }
        return new Candidate(skillName, score, "heuristic");
    }

    record RecommendationInput(String userId, String userInput) {
    }

    private record SkillRoutingRecommendation(String summary, Candidate candidate) {
    }
}
