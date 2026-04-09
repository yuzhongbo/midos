package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ParamSchema {

    private final Set<String> required;
    private final Set<String> atLeastOne;

    public ParamSchema(Set<String> required) {
        this(required, Set.of());
    }

    public ParamSchema(Set<String> required, Set<String> atLeastOne) {
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

    public Set<String> required() {
        return required;
    }

    public Set<String> atLeastOne() {
        return atLeastOne;
    }
}
