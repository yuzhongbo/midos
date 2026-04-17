package com.zhongbo.mindos.assistant.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CanonicalTaskFields {

    public static final String GOAL = "goal";
    public static final String DELIVERABLE = "deliverable";
    public static final String AUDIENCE = "audience";
    public static final String CONSTRAINTS = "constraints";
    public static final String DEADLINE = "deadline";
    public static final String SOURCE_REQUIREMENT = "sourceRequirement";
    public static final String NEXT_ACTION = "nextAction";
    public static final String BLOCKER = "blocker";
    public static final String DONE_DEFINITION = "doneDefinition";
    public static final String PROJECT = "project";
    public static final String TOPIC = "topic";
    public static final String OWNER = "owner";
    public static final String LOCATION = "location";

    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry(GOAL, List.of("goal", "task", "title", "objective", "request", "query", "taskFocus")),
            Map.entry(DELIVERABLE, List.of("deliverable", "artifact", "artifacts", "output", "expectedOutput", "result")),
            Map.entry(AUDIENCE, List.of("audience", "targetAudience", "userAudience", "recipient", "forWhom", "reader")),
            Map.entry(CONSTRAINTS, List.of("constraints", "constraint", "requirements", "requirement", "notes", "boundaries")),
            Map.entry(DEADLINE, List.of("deadline", "dueDate", "due", "date", "time", "scheduleTime")),
            Map.entry(SOURCE_REQUIREMENT, List.of("sourceRequirement", "sources", "source", "references", "reference", "docType")),
            Map.entry(NEXT_ACTION, List.of("nextAction", "next_step", "nextStep", "followUp")),
            Map.entry(BLOCKER, List.of("blocker", "blockingReason", "obstacle", "issue", "stuckPoint", "blockingPoint")),
            Map.entry(DONE_DEFINITION, List.of("doneDefinition", "definitionOfDone", "successCriteria", "acceptanceCriteria")),
            Map.entry(PROJECT, List.of("project")),
            Map.entry(TOPIC, List.of("topic")),
            Map.entry(OWNER, List.of("owner", "assignee")),
            Map.entry(LOCATION, List.of("location", "place"))
    );

    private CanonicalTaskFields() {
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(raw);
        ALIASES.forEach((canonicalKey, aliases) -> {
            if (hasValue(normalized.get(canonicalKey))) {
                return;
            }
            Object value = firstAliasValue(normalized, aliases);
            if (hasValue(value)) {
                normalized.put(canonicalKey, value);
            }
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    public static Object value(Map<String, Object> raw, String canonicalKey) {
        if (raw == null || raw.isEmpty() || canonicalKey == null || canonicalKey.isBlank()) {
            return null;
        }
        if (hasValue(raw.get(canonicalKey))) {
            return raw.get(canonicalKey);
        }
        return firstAliasValue(raw, ALIASES.getOrDefault(canonicalKey, List.of(canonicalKey)));
    }

    public static String text(Map<String, Object> raw, String canonicalKey) {
        return textValue(value(raw, canonicalKey));
    }

    public static String firstText(Map<String, Object> raw, String... canonicalKeys) {
        if (canonicalKeys == null || canonicalKeys.length == 0) {
            return "";
        }
        for (String key : canonicalKeys) {
            String text = text(raw, key);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public static String textValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            List<String> parts = new ArrayList<>();
            for (Object item : collection) {
                String text = textValue(item);
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
            return String.join("；", parts);
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = textValue(entry.getKey());
                String itemValue = textValue(entry.getValue());
                if (!key.isBlank() && !itemValue.isBlank()) {
                    parts.add(key + "=" + itemValue);
                }
            }
            return String.join(", ", parts);
        }
        String text = Objects.toString(value, "").trim();
        return text;
    }

    public static boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(CanonicalTaskFields::hasValue);
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return !Objects.toString(value, "").trim().isBlank();
    }

    private static Object firstAliasValue(Map<String, Object> raw, List<String> aliases) {
        if (raw == null || raw.isEmpty() || aliases == null || aliases.isEmpty()) {
            return null;
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            Object value = raw.get(alias);
            if (hasValue(value)) {
                return value;
            }
        }
        return null;
    }
}
