package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.command.EqCoachCommandSupport;
import com.zhongbo.mindos.assistant.common.command.FileSearchCommandSupport;
import com.zhongbo.mindos.assistant.common.command.NewsSearchCommandSupport;
import com.zhongbo.mindos.assistant.common.command.TeachingPlanCommandSupport;
import com.zhongbo.mindos.assistant.common.command.TodoCreateCommandSupport;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SkillCommandAssembler {

    private static final List<String> TEACHING_TOPIC_HINTS = List.of(
            "数学", "math", "英语", "english", "语文", "chinese", "物理", "physics",
            "化学", "chemistry", "生物", "biology", "历史", "history", "地理", "geography",
            "政治", "java", "python"
    );

    private final SkillDslParser skillDslParser;
    private final TodoCreateCommandSupport todoCreateCommandSupport;
    private final FileSearchCommandSupport fileSearchCommandSupport;
    private final EqCoachCommandSupport eqCoachCommandSupport;
    private final NewsSearchCommandSupport newsSearchCommandSupport;
    private final boolean preferenceReuseEnabled;

    SkillCommandAssembler(SkillDslParser skillDslParser,
                          boolean preferenceReuseEnabled) {
        this(skillDslParser, new TodoCreateCommandSupport(Clock.systemDefaultZone()), preferenceReuseEnabled);
    }

    SkillCommandAssembler(SkillDslParser skillDslParser,
                          TodoCreateCommandSupport todoCreateCommandSupport,
                          boolean preferenceReuseEnabled) {
        this.skillDslParser = skillDslParser;
        this.todoCreateCommandSupport = todoCreateCommandSupport == null
                ? new TodoCreateCommandSupport(Clock.systemDefaultZone())
                : todoCreateCommandSupport;
        this.fileSearchCommandSupport = new FileSearchCommandSupport();
        this.eqCoachCommandSupport = new EqCoachCommandSupport();
        this.newsSearchCommandSupport = new NewsSearchCommandSupport();
        this.preferenceReuseEnabled = preferenceReuseEnabled;
    }

    Map<String, Object> extractTeachingPlanPayload(String userInput) {
        return new LinkedHashMap<>(TeachingPlanCommandSupport.extractPayload(userInput));
    }

    Optional<SkillDsl> buildHabitSkillDsl(String skillName,
                                          String userInput,
                                          Map<String, Object> profileContext,
                                          boolean continuationOnlyInput,
                                          Optional<String> lastSuccessfulInput) {
        Map<String, Object> safeProfileContext = profileContext == null ? Map.of() : profileContext;
        Optional<String> safeLastSuccessfulInput = lastSuccessfulInput == null ? Optional.empty() : lastSuccessfulInput;
        switch (skillName) {
            case "teaching.plan":
                return Optional.of(new SkillDsl(skillName, buildTeachingPlanHabitPayload(userInput, safeProfileContext, safeLastSuccessfulInput)));
            case "code.generate":
                return Optional.of(new SkillDsl(skillName, buildCodeGenerateHabitPayload(userInput, safeProfileContext, continuationOnlyInput, safeLastSuccessfulInput)));
            case "todo.create":
                return Optional.of(new SkillDsl(skillName, buildTodoCreateHabitPayload(userInput, safeProfileContext, continuationOnlyInput, safeLastSuccessfulInput)));
            case "file.search":
                return Optional.of(new SkillDsl(skillName, buildFileSearchPayload(userInput)));
            case "news_search":
                return Optional.of(new SkillDsl(skillName, buildNewsSearchPayload(userInput)));
            case "echo", "time":
                return Optional.of(SkillDsl.of(skillName));
            default:
                return Optional.empty();
        }
    }

    Optional<SkillDsl> buildDetectedSkillDsl(String skillName,
                                             String userInput,
                                             Map<String, Object> existingAttributes) {
        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> seed = existingAttributes == null ? Map.of() : existingAttributes;
        SkillContext context = new SkillContext("", userInput, seed);
        return switch (skillName) {
            case "teaching.plan" -> Optional.of(new SkillDsl(skillName, mergeInto(seed, TeachingPlanCommandSupport.extractPayload(userInput))));
            case "todo.create" -> Optional.of(new SkillDsl(skillName, mergeInto(seed, todoCreateCommandSupport.extractPayload(userInput, asString(seed.get("timezone"))))));
            case "code.generate" -> Optional.of(new SkillDsl(skillName, mergeInto(seed, Map.of("task", sanitizeContinuationPrefix(userInput)))));
            case "eq.coach" -> Optional.of(new SkillDsl(skillName, mergeInto(seed, eqCoachCommandSupport.resolveAttributes(context))));
            case "file.search" -> Optional.of(new SkillDsl(skillName, mergeInto(seed, fileSearchCommandSupport.resolveAttributes(context))));
            case "news_search" -> Optional.of(new SkillDsl(skillName, mergeInto(seed, newsSearchCommandSupport.resolveAttributes(context))));
            case "echo", "time" -> Optional.of(SkillDsl.of(skillName));
            default -> Optional.empty();
        };
    }

    Map<String, Object> buildSemanticPayload(String targetSkill,
                                             Map<String, Object> seedPayload,
                                             String originalInput,
                                             String summary,
                                             String routingInput,
                                             String memoryHint) {
        if (targetSkill == null || targetSkill.isBlank()) {
            return seedPayload == null ? Map.of() : Map.copyOf(seedPayload);
        }
        Map<String, Object> payload = new LinkedHashMap<>(seedPayload == null ? Map.of() : seedPayload);
        switch (targetSkill) {
            case "code.generate" -> putIfBlank(payload, "task", firstNonBlank(asString(payload.get("task")), routingInput, originalInput));
            case "todo.create" -> {
                mergeMissing(payload, todoCreateCommandSupport.extractPayload(originalInput, asString(payload.get("timezone"))));
                putIfBlank(payload, "task", firstNonBlank(asString(payload.get("task")), summary, memoryHint, routingInput, originalInput));
            }
            case "eq.coach" -> {
                mergeMissing(payload, eqCoachCommandSupport.resolveAttributes(new SkillContext("", originalInput, payload)));
                putIfBlank(payload, "query", firstNonBlank(asString(payload.get("query")), summary, memoryHint, routingInput, originalInput));
            }
            case "teaching.plan" -> mergeMissing(payload, TeachingPlanCommandSupport.extractPayload(originalInput));
            case "file.search" -> {
                mergeMissing(payload, fileSearchCommandSupport.resolveAttributes(new SkillContext("", originalInput, payload)));
                putIfBlank(payload, "path", "./");
                putIfBlank(payload, "keyword", firstNonBlank(asString(payload.get("keyword")), summary, routingInput, originalInput));
            }
            case "news_search" -> {
                mergeMissing(payload, newsSearchCommandSupport.resolveAttributes(new SkillContext("", originalInput, payload)));
                putIfBlank(payload, "query", firstNonBlank(asString(payload.get("query")), asString(payload.get("keyword")), summary, routingInput, originalInput));
            }
            default -> {
            }
        }
        if (isMcpSearchSkill(targetSkill)) {
            putIfBlank(payload, "query", firstNonBlank(asString(payload.get("query")), summary, memoryHint, routingInput, originalInput));
        }
        return payload;
    }

    private Map<String, Object> buildTeachingPlanHabitPayload(String userInput,
                                                              Map<String, Object> profileContext,
                                                              Optional<String> lastSuccessfulInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        lastSuccessfulInput.ifPresent(historyInput -> mergeMissing(payload, TeachingPlanCommandSupport.extractPayload(historyInput)));
        Map<String, Object> currentPayload = extractTeachingPlanPayload(sanitizeContinuationPrefix(userInput));
        if (isContinuationIntent(userInput) && !hasExplicitTeachingTopic(userInput)) {
            currentPayload.remove("topic");
        }
        payload.putAll(currentPayload);
        mergeTeachingPlanFromProfile(payload, profileContext);
        return payload;
    }

    private Map<String, Object> buildCodeGenerateHabitPayload(String userInput,
                                                              Map<String, Object> profileContext,
                                                              boolean continuationOnlyInput,
                                                              Optional<String> lastSuccessfulInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String task = userInput;
        if (continuationOnlyInput) {
            task = lastSuccessfulInput
                    .map(historyInput -> resolveHistoricalValue("code.generate", historyInput, "task"))
                    .orElse(userInput);
        }
        payload.put("task", sanitizeContinuationPrefix(task));
        mergeCodeGenerateFromProfile(payload, profileContext);
        return payload;
    }

    private Map<String, Object> buildTodoCreateHabitPayload(String userInput,
                                                            Map<String, Object> profileContext,
                                                            boolean continuationOnlyInput,
                                                            Optional<String> lastSuccessfulInput) {
        Map<String, Object> payload = new LinkedHashMap<>(todoCreateCommandSupport.extractPayload(userInput, asString(profileContext.get("timezone"))));
        String historicalInput = continuationOnlyInput ? lastSuccessfulInput.orElse(null) : null;
        String task = asString(payload.get("task"));
        if (task == null || task.isBlank()) {
            task = continuationOnlyInput
                    ? firstNonBlank(resolveHistoricalValue("todo.create", historicalInput, "task"), userInput)
                    : userInput;
        }
        payload.put("task", sanitizeContinuationPrefix(task));
        if (isBlankValue(payload.get("dueDate")) && historicalInput != null) {
            putIfBlank(payload, "dueDate", resolveHistoricalValue("todo.create", historicalInput, "dueDate"));
        }
        if (isBlankValue(payload.get("priority")) && historicalInput != null) {
            putIfBlank(payload, "priority", resolveHistoricalValue("todo.create", historicalInput, "priority"));
        }
        if (isBlankValue(payload.get("reminder")) && historicalInput != null) {
            putIfBlank(payload, "reminder", resolveHistoricalValue("todo.create", historicalInput, "reminder"));
        }
        mergeTodoCreateFromProfile(payload, profileContext);
        return payload;
    }

    private Map<String, Object> buildFileSearchPayload(String userInput) {
        return new LinkedHashMap<>(fileSearchCommandSupport.resolveAttributes(new SkillContext("", userInput, Map.of())));
    }

    private Map<String, Object> buildNewsSearchPayload(String userInput) {
        return new LinkedHashMap<>(newsSearchCommandSupport.resolveAttributes(new SkillContext("", userInput, Map.of())));
    }

    private void mergeTeachingPlanFromProfile(Map<String, Object> payload, Map<String, Object> profileContext) {
        if (!preferenceReuseEnabled || profileContext == null || profileContext.isEmpty()) {
            return;
        }
        String role = asString(profileContext.get("role"));
        if (isBlankValue(payload.get("gradeOrLevel")) && role != null && !role.isBlank()) {
            payload.put("gradeOrLevel", role);
        }
        String style = asString(profileContext.get("style"));
        if (!payload.containsKey("learningStyle") && style != null && !style.isBlank()) {
            payload.put("learningStyle", List.of(style));
        }
        String timezone = asString(profileContext.get("timezone"));
        if (!payload.containsKey("constraints") && timezone != null && !timezone.isBlank()) {
            payload.put("constraints", List.of("时区:" + timezone));
        }
        String language = asString(profileContext.get("language"));
        if (!payload.containsKey("resourcePreference") && language != null && !language.isBlank()) {
            payload.put("resourcePreference", List.of("语言:" + language));
        }
    }

    private void mergeCodeGenerateFromProfile(Map<String, Object> payload, Map<String, Object> profileContext) {
        if (!preferenceReuseEnabled || profileContext == null || profileContext.isEmpty()) {
            return;
        }
        String style = asString(profileContext.get("style"));
        if (style != null && !style.isBlank() && !payload.containsKey("style")) {
            payload.put("style", style);
        }
        String language = asString(profileContext.get("language"));
        if (language != null && !language.isBlank() && !payload.containsKey("language")) {
            payload.put("language", language);
        }
    }

    private void mergeTodoCreateFromProfile(Map<String, Object> payload, Map<String, Object> profileContext) {
        if (!preferenceReuseEnabled || profileContext == null || profileContext.isEmpty()) {
            return;
        }
        String timezone = asString(profileContext.get("timezone"));
        if (timezone != null && !timezone.isBlank() && !payload.containsKey("timezone")) {
            payload.put("timezone", timezone);
        }
        String style = asString(profileContext.get("style"));
        if (style != null && !style.isBlank() && !payload.containsKey("style")) {
            payload.put("style", style);
        }
    }

    private String resolveHistoricalValue(String skillName, String historicalInput, String fieldName) {
        if (historicalInput == null || historicalInput.isBlank()) {
            return "";
        }
        try {
            Optional<SkillDsl> parsed = skillDslParser.parse(historicalInput);
            if (parsed.isPresent() && skillName.equals(parsed.get().skill())) {
                Object value = parsed.get().input().get(fieldName);
                if (!isBlankValue(value)) {
                    return String.valueOf(value).trim();
                }
            }
        } catch (SkillDslValidationException ignored) {
            // Historical inputs may be natural language instead of explicit SkillDSL.
        }
        if ("todo.create".equals(skillName)) {
            Object value = todoCreateCommandSupport.extractPayload(historicalInput, "").get(fieldName);
            return value == null ? "" : String.valueOf(value).trim();
        }
        return "task".equals(fieldName) ? historicalInput : "";
    }

    private void mergeMissing(Map<String, Object> payload, Map<String, Object> additions) {
        if (payload == null || additions == null || additions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            if (isBlankValue(payload.get(entry.getKey())) && !isBlankValue(entry.getValue())) {
                payload.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, Object> mergeInto(Map<String, Object> base, Map<String, Object> additions) {
        Map<String, Object> payload = new LinkedHashMap<>(base == null ? Map.of() : base);
        mergeMissing(payload, additions);
        return payload;
    }

    private void putIfBlank(Map<String, Object> payload, String key, Object value) {
        if (payload == null || key == null || key.isBlank() || isBlankValue(value)) {
            return;
        }
        if (isBlankValue(payload.get(key))) {
            payload.put(key, value);
        }
    }

    private boolean isMcpSearchSkill(String skillName) {
        String normalized = normalize(skillName);
        return normalized.startsWith("mcp.")
                && (normalized.contains("search") || normalized.endsWith("query"));
    }

    private boolean isContinuationIntent(String input) {
        String normalized = normalize(input);
        return normalized.startsWith("继续")
                || normalized.startsWith("按之前")
                || normalized.startsWith("按上次")
                || normalized.startsWith("沿用")
                || normalized.startsWith("还是那个")
                || normalized.startsWith("同样方式");
    }

    private boolean hasExplicitTeachingTopic(String input) {
        String normalized = normalize(input);
        for (String hint : TEACHING_TOPIC_HINTS) {
            if (normalized.contains(normalize(hint))) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank();
    }

    private String sanitizeContinuationPrefix(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("^(继续|按之前|按上次|沿用|同样方式|还是那个)[，,、 ]*", "").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
