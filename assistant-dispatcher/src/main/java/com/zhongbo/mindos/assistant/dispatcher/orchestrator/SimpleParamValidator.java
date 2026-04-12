package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SimpleParamValidator implements ParamValidator {

    private static final Pattern INTEGER_PATTERN = Pattern.compile("(-?\\d+)");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern MISSING_PARAMS_PATTERN = Pattern.compile("(?:缺少必填参数|至少需要提供以下参数之一):\\s*([^。\\n]+)");

    private final ParamSchemaRegistry registry;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;

    public SimpleParamValidator(ParamSchemaRegistry registry) {
        this(registry, new DispatcherMemoryFacade(null, null, null));
    }

    public SimpleParamValidator(ParamSchemaRegistry registry, MemoryGateway memoryGateway) {
        this(registry, new DispatcherMemoryFacade(memoryGateway, null, null));
    }

    @Autowired
    public SimpleParamValidator(ParamSchemaRegistry registry, DispatcherMemoryFacade dispatcherMemoryFacade) {
        this.registry = registry;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade == null
                ? new DispatcherMemoryFacade(null, null, null)
                : dispatcherMemoryFacade;
    }

    @Override
    public ValidationResult validate(String target,
                                     Map<String, Object> params,
                                     DecisionOrchestrator.OrchestrationRequest request) {
        if (target == null || target.isBlank()) {
            return ValidationResult.error("missing target");
        }
        ValidationResult targetValidation = validateTarget(target);
        if (!targetValidation.valid()) {
            return targetValidation;
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        Optional<ParamSchema> schema = registry.find(target);
        if (schema.isEmpty()) {
            return ValidationResult.ok(safeParams, Map.of());
        }
        return validateAgainstSchema(target, schema.get(), safeParams, request, Set.of());
    }

    @Override
    public ValidationResult repairAfterFailure(String target,
                                               Map<String, Object> params,
                                               SkillResult failure,
                                               DecisionOrchestrator.OrchestrationRequest request) {
        if (target == null || target.isBlank()) {
            return ValidationResult.error("missing target");
        }
        ValidationResult targetValidation = validateTarget(target);
        if (!targetValidation.valid()) {
            return targetValidation;
        }
        Optional<ParamSchema> schema = registry.find(target);
        if (schema.isEmpty()) {
            return validate(target, params, request);
        }
        return validateAgainstSchema(target, schema.get(), params == null ? Map.of() : params, request, parseMissingKeys(failure));
    }

    private ValidationResult validateTarget(String target) {
        if (target == null || target.isBlank() || !target.startsWith("mcp.")) {
            return ValidationResult.ok();
        }
        String[] parts = target.split("\\.");
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return ValidationResult.error("MCP 名称需为 mcp.<alias>.<tool>");
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateAgainstSchema(String target,
                                                   ParamSchema schema,
                                                   Map<String, Object> params,
                                                   DecisionOrchestrator.OrchestrationRequest request,
                                                   Set<String> extraRequired) {
        Map<String, Object> normalized = new LinkedHashMap<>(params);
        normalized.putAll(resolveFromContext(schema, normalized, request, extraRequired));
        normalized.putAll(resolveFromMemory(schema, normalized, request, extraRequired));
        applyDefaults(schema, normalized);
        coerceTypes(schema, normalized);

        Map<String, Object> autofilled = diff(params, normalized);
        List<String> missingRequired = missingRequired(schema.required(), normalized, extraRequired);
        if (!missingRequired.isEmpty()) {
            return ValidationResult.clarify("缺少必填参数: " + String.join(",", missingRequired), missingRequired, normalized, autofilled);
        }
        ValidationResult atLeastOne = validateAtLeastOne(schema.atLeastOne(), normalized, autofilled);
        if (!atLeastOne.valid()) {
            return new ValidationResult(false, true, normalized, atLeastOne.missingParams(), autofilled, atLeastOne.message());
        }
        ValidationResult rangeValidation = validateNumericRanges(schema.numericRanges(), normalized, autofilled);
        if (!rangeValidation.valid()) {
            return rangeValidation;
        }
        return ValidationResult.ok(normalized, autofilled);
    }

    private Map<String, Object> resolveFromContext(ParamSchema schema,
                                                   Map<String, Object> currentParams,
                                                   DecisionOrchestrator.OrchestrationRequest request,
                                                   Set<String> extraRequired) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, Object> requestAttributes = request == null || request.skillContext() == null
                ? Map.of()
                : request.skillContext().attributes();
        Map<String, Object> profileContext = request == null ? Map.of() : request.safeProfileContext();
        Set<String> candidateKeys = collectCandidateKeys(schema, extraRequired);
        for (String key : candidateKeys) {
            if (hasValue(currentParams, key)) {
                continue;
            }
            Object value = findByAliases(key, schema.aliases(), currentParams, requestAttributes, profileContext);
            if (!isBlank(value)) {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    private Map<String, Object> resolveFromMemory(ParamSchema schema,
                                                  Map<String, Object> currentParams,
                                                  DecisionOrchestrator.OrchestrationRequest request,
                                                  Set<String> extraRequired) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        Set<String> candidateKeys = collectCandidateKeys(schema, extraRequired);
        for (String key : candidateKeys) {
            if (hasValue(currentParams, key)) {
                continue;
            }
            dispatcherMemoryFacade.infer(request.userId(), key, request.userInput())
                    .ifPresent(value -> resolved.put(key, value));
        }
        return resolved;
    }

    private void applyDefaults(ParamSchema schema, Map<String, Object> normalized) {
        schema.defaults().forEach((key, value) -> {
            if (!hasValue(normalized, key) && !isBlank(value)) {
                normalized.put(key, value);
            }
        });
    }

    private void coerceTypes(ParamSchema schema, Map<String, Object> normalized) {
        schema.types().forEach((key, type) -> {
            if (!hasValue(normalized, key) || type == null) {
                return;
            }
            Object coerced = coerceValue(type, normalized.get(key));
            if (!isBlank(coerced)) {
                normalized.put(key, coerced);
            }
        });
    }

    private Object findByAliases(String key,
                                 Map<String, List<String>> aliases,
                                 Map<String, Object> currentParams,
                                 Map<String, Object> attributes,
                                 Map<String, Object> profileContext) {
        if (hasValue(currentParams, key)) {
            return currentParams.get(key);
        }
        if (hasValue(attributes, key)) {
            return attributes.get(key);
        }
        if (hasValue(profileContext, key)) {
            return profileContext.get(key);
        }
        for (String alias : aliases.getOrDefault(key, List.of())) {
            if (hasValue(currentParams, alias)) {
                return currentParams.get(alias);
            }
            if (hasValue(attributes, alias)) {
                return attributes.get(alias);
            }
            if (hasValue(profileContext, alias)) {
                return profileContext.get(alias);
            }
        }
        return null;
    }

    private Object coerceValue(ParamType type, Object value) {
        if (value == null || type == null) {
            return value;
        }
        return switch (type) {
            case STRING -> String.valueOf(value).trim();
            case INTEGER -> {
                if (value instanceof Number number) {
                    yield number.intValue();
                }
                Matcher matcher = INTEGER_PATTERN.matcher(String.valueOf(value));
                yield matcher.find() ? Integer.parseInt(matcher.group(1)) : value;
            }
            case DOUBLE -> {
                if (value instanceof Number number) {
                    yield number.doubleValue();
                }
                Matcher matcher = DOUBLE_PATTERN.matcher(String.valueOf(value));
                yield matcher.find() ? Double.parseDouble(matcher.group(1)) : value;
            }
            case BOOLEAN -> {
                if (value instanceof Boolean bool) {
                    yield bool;
                }
                String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "是".equals(normalized)) {
                    yield Boolean.TRUE;
                }
                if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "否".equals(normalized)) {
                    yield Boolean.FALSE;
                }
                yield value;
            }
        };
    }

    private ValidationResult validateAtLeastOne(Set<String> candidates,
                                                Map<String, Object> params,
                                                Map<String, Object> autofilled) {
        if (candidates == null || candidates.isEmpty()) {
            return ValidationResult.ok(params, autofilled);
        }
        boolean present = candidates.stream().anyMatch(key -> hasValue(params, key));
        if (present) {
            return ValidationResult.ok(params, autofilled);
        }
        List<String> missing = candidates.stream().sorted().toList();
        return ValidationResult.clarify("至少需要提供以下参数之一: " + String.join(",", missing), missing, params, autofilled);
    }

    private ValidationResult validateNumericRanges(Map<String, ParamSchema.NumericRange> numericRanges,
                                                   Map<String, Object> params,
                                                   Map<String, Object> autofilled) {
        if (numericRanges == null || numericRanges.isEmpty()) {
            return ValidationResult.ok(params, autofilled);
        }
        List<String> invalidKeys = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        numericRanges.forEach((key, range) -> {
            if (!hasValue(params, key) || range == null) {
                return;
            }
            Double value = asNumber(params.get(key));
            if (value == null) {
                return;
            }
            if (value < range.minInclusive() || value > range.maxInclusive()) {
                invalidKeys.add(key);
                reasons.add(key + " 需在 " + range.describe());
            }
        });
        if (invalidKeys.isEmpty()) {
            return ValidationResult.ok(params, autofilled);
        }
        return ValidationResult.clarify("参数超出范围: " + String.join("; ", reasons), invalidKeys, params, autofilled);
    }

    private List<String> missingRequired(Set<String> required,
                                         Map<String, Object> params,
                                         Set<String> extraRequired) {
        Set<String> allRequired = new LinkedHashSet<>();
        if (required != null) {
            allRequired.addAll(required);
        }
        if (extraRequired != null) {
            allRequired.addAll(extraRequired);
        }
        return allRequired.stream()
                .filter(key -> !hasValue(params, key))
                .sorted()
                .toList();
    }

    private Set<String> parseMissingKeys(SkillResult failure) {
        if (failure == null || failure.output() == null || failure.output().isBlank()) {
            return Set.of();
        }
        Matcher matcher = MISSING_PARAMS_PATTERN.matcher(failure.output());
        if (!matcher.find()) {
            return Set.of();
        }
        return java.util.Arrays.stream(matcher.group(1).split("[,，]"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Object> diff(Map<String, Object> original, Map<String, Object> normalized) {
        Map<String, Object> diff = new LinkedHashMap<>();
        Map<String, Object> safeOriginal = original == null ? Map.of() : original;
        normalized.forEach((key, value) -> {
            Object existing = safeOriginal.get(key);
            if (!java.util.Objects.equals(existing, value)) {
                diff.put(key, value);
            }
        });
        return Map.copyOf(diff);
    }

    private Set<String> collectCandidateKeys(ParamSchema schema, Set<String> extraRequired) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(schema.required());
        keys.addAll(schema.atLeastOne());
        keys.addAll(schema.defaults().keySet());
        keys.addAll(schema.types().keySet());
        keys.addAll(schema.aliases().keySet());
        keys.addAll(schema.numericRanges().keySet());
        if (extraRequired != null) {
            keys.addAll(extraRequired);
        }
        return keys;
    }

    private Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        Matcher integerMatcher = INTEGER_PATTERN.matcher(String.valueOf(value));
        if (integerMatcher.find()) {
            return Double.parseDouble(integerMatcher.group(1));
        }
        Matcher doubleMatcher = DOUBLE_PATTERN.matcher(String.valueOf(value));
        if (doubleMatcher.find()) {
            return Double.parseDouble(doubleMatcher.group(1));
        }
        return null;
    }

    private boolean hasValue(Map<String, Object> params, String key) {
        return params != null && key != null && params.containsKey(key) && !isBlank(params.get(key));
    }

    private boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        String text = String.valueOf(value);
        return text.isBlank();
    }
}
