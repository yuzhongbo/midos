package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeObject(String objectId,
                            RuntimeObjectType type,
                            String capability,
                            Map<String, Object> metadata) {

    public RuntimeObject {
        objectId = objectId == null ? "" : objectId.trim();
        type = type == null ? RuntimeObjectType.TASK : type;
        capability = capability == null ? "" : capability.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
