package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ParamSchema {

    private final Set<String> required;

    public ParamSchema(Set<String> required) {
        if (required == null || required.isEmpty()) {
            this.required = Set.of();
        } else {
            this.required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
        }
    }

    public Set<String> required() {
        return required;
    }
}
