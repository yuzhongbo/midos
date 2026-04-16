package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchema;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamType;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class HermesToolSchema {

    private final String name;
    private final String description;
    private final List<String> routingKeywords;
    private final ParamSchema paramSchema;

    private HermesToolSchema(String name,
                             String description,
                             List<String> routingKeywords,
                             ParamSchema paramSchema) {
        this.name = normalize(name);
        this.description = normalize(description);
        this.routingKeywords = routingKeywords == null ? List.of() : List.copyOf(routingKeywords.stream()
                .map(HermesToolSchema::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList());
        this.paramSchema = paramSchema;
    }

    static HermesToolSchema fromDescriptor(SkillDescriptor descriptor, ParamSchema paramSchema) {
        if (descriptor == null) {
            return of("", "", paramSchema);
        }
        return new HermesToolSchema(descriptor.name(), descriptor.description(), descriptor.routingKeywords(), paramSchema);
    }

    static HermesToolSchema of(String name, String description, ParamSchema paramSchema) {
        return new HermesToolSchema(name, description, List.of(), paramSchema);
    }

    String name() {
        return name;
    }

    int routingScore(String input) {
        String normalizedInput = normalizeForMatch(input);
        if (normalizedInput.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int bestScore = Integer.MIN_VALUE;
        for (String phrase : detectionPhrases()) {
            String normalizedPhrase = normalizeForMatch(phrase);
            if (normalizedPhrase.isBlank()) {
                continue;
            }
            if (normalizedInput.equals(normalizedPhrase)) {
                bestScore = Math.max(bestScore, 900 + normalizedPhrase.length());
                continue;
            }
            if (normalizedInput.startsWith(normalizedPhrase + " ")) {
                bestScore = Math.max(bestScore, 820 + normalizedPhrase.length());
                continue;
            }
            if (normalizedInput.contains(normalizedPhrase)) {
                bestScore = Math.max(bestScore, 600 + normalizedPhrase.length());
                continue;
            }
            int overlap = countMatchedWords(normalizedInput, normalizedPhrase);
            if (overlap > 0) {
                bestScore = Math.max(bestScore, 300 + overlap * 40);
            }
        }
        return bestScore;
    }

    boolean matches(String target) {
        return !name.isBlank() && name.equals(normalize(target));
    }

    String semanticSummary() {
        StringBuilder summary = new StringBuilder(name);
        if (!description.isBlank()) {
            summary.append(" - ").append(description);
        }
        List<String> details = new ArrayList<>();
        if (!routingKeywords.isEmpty()) {
            details.add("keywords=" + String.join("/", routingKeywords.stream().limit(6).toList()));
        }
        if (paramSchema != null) {
            if (!paramSchema.required().isEmpty()) {
                details.add("required=" + join(paramSchema.required()));
            }
            if (!paramSchema.atLeastOne().isEmpty()) {
                details.add("oneOf=" + join(paramSchema.atLeastOne()));
            }
            if (!paramSchema.types().isEmpty()) {
                details.add("types=" + describeTypes(paramSchema.types()));
            }
            if (!paramSchema.defaults().isEmpty()) {
                details.add("defaults=" + describeDefaults(paramSchema.defaults()));
            }
        }
        if (!details.isEmpty()) {
            summary.append(" | ").append(String.join(" | ", details));
        }
        return summary.toString();
    }

    private static String describeTypes(Map<String, ParamType> types) {
        return types.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().name().toLowerCase())
                .collect(Collectors.joining(","));
    }

    private static String describeDefaults(Map<String, Object> defaults) {
        return defaults.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private static String join(Set<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private List<String> detectionPhrases() {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        phrases.add(name);
        phrases.add(splitIdentifier(name));
        phrases.addAll(routingKeywords);
        return List.copyOf(phrases);
    }

    private static int countMatchedWords(String input, String keyword) {
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

    private static boolean isSignificant(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        long count = value.codePoints().filter(Character::isLetterOrDigit).count();
        return count >= 2;
    }

    private static String splitIdentifier(String value) {
        return value == null ? ""
                : value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[._-]+", " ");
    }

    private static String normalizeForMatch(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
