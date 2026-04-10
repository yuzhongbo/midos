package com.zhongbo.mindos.assistant.dispatcher.agent.procedure;

import java.util.List;

public record Procedure(String id,
                        String intent,
                        String trigger,
                        List<String> steps,
                        double successRate,
                        int reuseCount) {

    public Procedure {
        id = id == null ? "" : id.trim();
        intent = intent == null ? "" : intent.trim();
        trigger = trigger == null ? "" : trigger.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
        successRate = Math.max(0.0, Math.min(1.0, successRate));
        reuseCount = Math.max(0, reuseCount);
    }
}
