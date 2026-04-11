package com.zhongbo.mindos.assistant.dispatcher.routing;

public enum RoutingStage {
    PREPARING,
    ROUTING,
    VALIDATING,
    EXECUTING,
    POST_EXECUTION,
    FALLBACK,
    MULTI_AGENT
}
