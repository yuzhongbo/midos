package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import java.util.List;
import java.util.Map;

public record ProcedureStepTemplate(String target,
                                    Map<String, Object> params,
                                    List<String> dependsOn,
                                    String saveAs) {

    public ProcedureStepTemplate {
        target = target == null ? "" : target.trim();
        params = params == null ? Map.of() : Map.copyOf(params);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        saveAs = saveAs == null ? "" : saveAs.trim();
    }
}
