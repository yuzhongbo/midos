package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ParamSchema {

    private final Set<String> required;
    private final Set<String> atLeastOne;
    private final Map<String, Object> defaults;
    private final Map<String, ParamType> types;
    private final Map<String, List<String>> aliases;
    private final Map<String, NumericRange> numericRanges;

    public ParamSchema(Set<String> required) {
        this(required, Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public ParamSchema(Set<String> required, Set<String> atLeastOne) {
        this(required, atLeastOne, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public ParamSchema(Set<String> required,
                       Set<String> atLeastOne,
                       Map<String, Object> defaults,
                       Map<String, ParamType> types,
                       Map<String, List<String>> aliases,
                       Map<String, NumericRange> numericRanges) {
        if (required == null || required.isEmpty()) {
            this.required = Set.of();
        } else {
            this.required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
        }
        if (atLeastOne == null || atLeastOne.isEmpty()) {
            this.atLeastOne = Set.of();
        } else {
            this.atLeastOne = Collections.unmodifiableSet(new LinkedHashSet<>(atLeastOne));
        }
        if (defaults == null || defaults.isEmpty()) {
            this.defaults = Map.of();
        } else {
            this.defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        }
        if (types == null || types.isEmpty()) {
            this.types = Map.of();
        } else {
            this.types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        }
        if (aliases == null || aliases.isEmpty()) {
            this.aliases = Map.of();
        } else {
            Map<String, List<String>> normalized = new LinkedHashMap<>();
            aliases.forEach((key, values) -> {
                if (key == null || key.isBlank() || values == null || values.isEmpty()) {
                    return;
                }
                normalized.put(key, List.copyOf(values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList()));
            });
            this.aliases = Collections.unmodifiableMap(normalized);
        }
        if (numericRanges == null || numericRanges.isEmpty()) {
            this.numericRanges = Map.of();
        } else {
            this.numericRanges = Collections.unmodifiableMap(new LinkedHashMap<>(numericRanges));
        }
    }

    public static ParamSchema required(Set<String> required) {
        return new ParamSchema(required, Set.of());
    }

    public static ParamSchema atLeastOne(String... keys) {
        if (keys == null || keys.length == 0) {
            return new ParamSchema(Set.of(), Set.of());
        }
        Set<String> group = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                group.add(key);
            }
        }
        return new ParamSchema(Set.of(), group);
    }

    public static ParamSchema of(Set<String> required, Set<String> atLeastOne) {
        return new ParamSchema(required, atLeastOne);
    }

    public ParamSchema withDefaults(Map<String, Object> defaults) {
        return new ParamSchema(required, atLeastOne, defaults, types, aliases, numericRanges);
    }

    public ParamSchema withTypes(Map<String, ParamType> types) {
        return new ParamSchema(required, atLeastOne, defaults, types, aliases, numericRanges);
    }

    public ParamSchema withAliases(Map<String, List<String>> aliases) {
        return new ParamSchema(required, atLeastOne, defaults, types, aliases, numericRanges);
    }

    public ParamSchema withNumericRanges(Map<String, NumericRange> numericRanges) {
        return new ParamSchema(required, atLeastOne, defaults, types, aliases, numericRanges);
    }

    public Set<String> required() {
        return required;
    }

    public Set<String> atLeastOne() {
        return atLeastOne;
    }

    public Map<String, Object> defaults() {
        return defaults;
    }

    public Map<String, ParamType> types() {
        return types;
    }

    public Map<String, List<String>> aliases() {
        return aliases;
    }

    public Map<String, NumericRange> numericRanges() {
        return numericRanges;
    }

    public record NumericRange(double minInclusive, double maxInclusive) {
        public NumericRange {
            if (Double.isNaN(minInclusive) || Double.isNaN(maxInclusive) || maxInclusive < minInclusive) {
                throw new IllegalArgumentException("invalid numeric range");
            }
        }

        public static NumericRange closed(double minInclusive, double maxInclusive) {
            return new NumericRange(minInclusive, maxInclusive);
        }

        public String describe() {
            return format(minInclusive) + "-" + format(maxInclusive);
        }

        private static String format(double value) {
            if (Math.rint(value) == value) {
                return Integer.toString((int) value);
            }
            return Double.toString(value);
        }
    }
}
