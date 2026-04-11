package com.zhongbo.mindos.assistant.skill;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class SkillRegistry {

    public record DetectedSkill(Skill skill, int score) {
    }

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final SkillRoutingProperties routingProperties;

    public SkillRegistry(List<Skill> discoveredSkills) {
        this(discoveredSkills, new SkillRoutingProperties());
    }

    @Autowired
    public SkillRegistry(List<Skill> discoveredSkills, SkillRoutingProperties routingProperties) {
        this.routingProperties = routingProperties == null ? new SkillRoutingProperties() : routingProperties;
        discoveredSkills.forEach(this::register);
    }

    public synchronized void register(Skill skill) {
        skills.put(skill.name(), skill);
    }

    public synchronized boolean unregister(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        return skills.remove(skillName) != null;
    }

    public synchronized boolean containsSkill(String skillName) {
        return skillName != null && skills.containsKey(skillName);
    }

    public synchronized int unregisterByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return 0;
        }
        int removed = 0;
        for (String key : List.copyOf(skills.keySet())) {
            if (key.startsWith(prefix)) {
                if (skills.remove(key) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public synchronized Optional<Skill> getSkill(String skillName) {
        return Optional.ofNullable(skills.get(skillName));
    }

    public synchronized Collection<Skill> getAllSkills() {
        return List.copyOf(skills.values());
    }

    public synchronized int size() {
        return skills.size();
    }

    public synchronized List<String> resolvedRoutingKeywords(String skillName) {
        Skill skill = skills.get(skillName);
        if (skill == null) {
            return List.of();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add(skill.name());
        merged.add(splitSkillName(skill.name()));
        merged.addAll(skill.routingKeywords());
        merged.addAll(configuredKeywords(skill.name()));
        return merged.stream()
                .map(this::normalize)
                .filter(keyword -> !keyword.isBlank())
                .toList();
    }

    public synchronized int routingScore(String skillName, String input) {
        Skill skill = skills.get(skillName);
        if (skill == null) {
            return Integer.MIN_VALUE;
        }
        int customScore = skill.routingScore(input);
        int keywordScore = keywordRoutingScore(skill.name(), input);
        return Math.max(customScore, keywordScore);
    }

    public synchronized Optional<Skill> detect(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String normalized = input.trim();
        String firstToken = normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

        Skill directMatch = skills.get(firstToken);
        if (directMatch != null) {
            return Optional.of(directMatch);
        }

        Skill bestMatch = null;
        int bestScore = Integer.MIN_VALUE;
        for (Skill skill : skills.values()) {
            int score = routingScore(skill.name(), normalized);
            if (score > bestScore) {
                bestMatch = skill;
                bestScore = score;
            }
        }
        return bestScore > 0 && bestMatch != null ? Optional.of(bestMatch) : Optional.empty();
    }

    public synchronized List<DetectedSkill> detectCandidates(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        String normalized = input.trim();
        String firstToken = normalized.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

        List<DetectedSkill> candidates = new java.util.ArrayList<>();
        for (Skill skill : skills.values()) {
            int score = routingScore(skill.name(), normalized);
            if (skill.name().equalsIgnoreCase(firstToken)) {
                score = Math.max(score, 1000);
            }
            if (score > 0) {
                candidates.add(new DetectedSkill(skill, score));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.sort(Comparator
                .comparingInt(DetectedSkill::score).reversed()
                .thenComparing(candidate -> candidate.skill().name()));
        int safeLimit = Math.min(limit, candidates.size());
        return List.copyOf(candidates.subList(0, safeLimit));
    }

    private List<String> configuredKeywords(String skillName) {
        if (routingProperties.getKeywords().isEmpty()) {
            return List.of();
        }
        String raw = routingProperties.getKeywords().get(skillName);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private int keywordRoutingScore(String skillName, String input) {
        String normalizedInput = normalize(input);
        if (normalizedInput.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int bestScore = Integer.MIN_VALUE;
        for (String keyword : resolvedRoutingKeywords(skillName)) {
            if (keyword.isBlank()) {
                continue;
            }
            if (normalizedInput.equals(keyword)) {
                bestScore = Math.max(bestScore, 900 + keyword.length());
                continue;
            }
            if (normalizedInput.contains(keyword)) {
                bestScore = Math.max(bestScore, 600 + keyword.length());
                continue;
            }
            int overlap = countMatchedWords(normalizedInput, keyword);
            if (overlap > 0) {
                bestScore = Math.max(bestScore, 300 + overlap * 40);
            }
        }
        return bestScore;
    }

    private int countMatchedWords(String input, String keyword) {
        int matches = 0;
        for (String part : keyword.split(" ")) {
            if (!isSignificant(part)) {
                continue;
            }
            if (input.contains(part)) {
                matches++;
            }
        }
        return matches;
    }

    private boolean isSignificant(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        long count = value.codePoints().filter(Character::isLetterOrDigit).count();
        return count >= 2;
    }

    private String splitSkillName(String skillName) {
        return skillName == null ? "" : skillName.replaceAll("[._-]+", " ");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
