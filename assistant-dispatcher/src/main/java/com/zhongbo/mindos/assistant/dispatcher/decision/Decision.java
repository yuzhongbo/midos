package com.zhongbo.mindos.assistant.dispatcher.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Decision(String intent, String target, Map<String, Object> params, double confidence, boolean requireClarify) {

    public Decision {
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        params = Collections.unmodifiableMap(new LinkedHashMap<>(safeParams));
    }

    public boolean needClarify() {
        return requireClarify;
    }
}
