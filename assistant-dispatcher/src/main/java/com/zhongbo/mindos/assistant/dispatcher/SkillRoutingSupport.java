package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class SkillRoutingSupport {

    private static final Pattern ROUTING_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}.#_-]+");

    private final SkillEngineFacade skillEngine;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final int shortlistMaxSkills;
    private final Function<String, String> memoryBucketResolver;

    SkillRoutingSupport(SkillEngineFacade skillEngine,
                        DispatcherMemoryFacade dispatcherMemoryFacade,
                        BehaviorRoutingSupport behaviorRoutingSupport,
                        int shortlistMaxSkills,
                        Function<String, String> memoryBucketResolver) {
        this.skillEngine = skillEngine;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.shortlistMaxSkills = Math.max(1, shortlistMaxSkills);
        this.memoryBucketResolver = memoryBucketResolver;
    }

    String describeSkillRoutingCandidates(String userId, String userInput) {
        List<SkillRoutingCandidate> rankedCandidates = rankSkillRoutingCandidates(userId, userInput);
        if (rankedCandidates.isEmpty()) {
            return "";
        }

        List<String> shortlisted = rankedCandidates.stream()
                .filter(candidate -> candidate.score() > 0)
                .limit(shortlistMaxSkills)
                .map(SkillRoutingCandidate::summary)
                .toList();
        if (shortlisted.isEmpty()) {
            shortlisted = rankedCandidates.stream()
                    .limit(shortlistMaxSkills)
                    .map(SkillRoutingCandidate::summary)
                    .toList();
        }

        return shortlisted.stream()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    int bestSkillRoutingScore(String userId, String userInput) {
        return rankSkillRoutingCandidates(userId, userInput).stream()
                .mapToInt(SkillRoutingCandidate::score)
                .max()
                .orElse(0);
    }

    private List<SkillRoutingCandidate> rankSkillRoutingCandidates(String userId, String userInput) {
        List<String> summaries = skillEngine.listAvailableSkillSummaries();
        if (summaries.isEmpty()) {
            return List.of();
        }
        Set<String> inputTokens = routingTokens(userInput);
        String memoryBucket = resolveMemoryBucket(userInput);
        Optional<String> preferredFromStats = behaviorRoutingSupport.preferredSkillFromStats(userId);
        Optional<String> preferredFromHistory = behaviorRoutingSupport.preferredSkillFromHistory(
                dispatcherMemoryFacade.getSkillUsageHistory(userId)
        );
        String normalizedInput = normalize(userInput);

        return summaries.stream()
                .map(summary -> new SkillRoutingCandidate(summary, skillRoutingScore(
                        summary,
                        normalizedInput,
                        inputTokens,
                        memoryBucket,
                        preferredFromStats,
                        preferredFromHistory)))
                .sorted(Comparator.comparingInt(SkillRoutingCandidate::score).reversed()
                        .thenComparing(SkillRoutingCandidate::summary))
                .toList();
    }

    private int skillRoutingScore(String summary,
                                  String normalizedInput,
                                  Set<String> inputTokens,
                                  String memoryBucket,
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
        score += bucketRoutingBoost(memoryBucket, normalizedSkillName);
        return score;
    }

    private int bucketRoutingBoost(String memoryBucket, String skillName) {
        return switch (memoryBucket) {
            case "learning" -> "teaching.plan".equals(skillName) ? 80 : 0;
            case "eq" -> "eq.coach".equals(skillName) ? 80 : 0;
            case "task" -> "todo.create".equals(skillName) ? 60 : 0;
            case "coding" -> {
                if ("code.generate".equals(skillName)) {
                    yield 80;
                }
                yield "file.search".equals(skillName) ? 40 : 0;
            }
            default -> 0;
        };
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

    private String resolveMemoryBucket(String input) {
        if (memoryBucketResolver == null) {
            return "general";
        }
        String resolved = memoryBucketResolver.apply(input);
        return resolved == null || resolved.isBlank() ? "general" : resolved;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private record SkillRoutingCandidate(String summary, int score) {
    }
}
