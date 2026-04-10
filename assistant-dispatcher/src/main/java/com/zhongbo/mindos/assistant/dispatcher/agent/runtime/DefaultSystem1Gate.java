package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

public class DefaultSystem1Gate implements System1Gate {

    private final double minConfidence;

    public DefaultSystem1Gate() {
        this(0.78);
    }

    public DefaultSystem1Gate(double minConfidence) {
        this.minConfidence = Math.max(0.0, Math.min(1.0, minConfidence));
    }

    @Override
    public boolean shouldUseFastPath(AgentDispatchRequest request) {
        if (request == null || request.decision() == null) {
            return false;
        }
        return !request.decision().target().isBlank()
                && !request.decision().requireClarify()
                && request.decision().confidence() >= minConfidence
                && (request.decision().params() == null || !request.decision().params().containsKey("tasks"));
    }
}
