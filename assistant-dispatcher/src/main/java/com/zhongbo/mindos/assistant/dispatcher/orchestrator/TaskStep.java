package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Map;

public record TaskStep(String id,
                       String target,
                       Map<String, Object> params,
                       String saveAs,
                       boolean optional) {

    public TaskStep {
        params = params == null ? Map.of() : Map.copyOf(params);
        saveAs = saveAs == null ? "" : saveAs.trim();
        id = id == null || id.isBlank() ? target : id.trim();
    }
}
