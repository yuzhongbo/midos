package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Optional;

public interface ParamSchemaRegistry {

    Optional<ParamSchema> find(String target);
}
