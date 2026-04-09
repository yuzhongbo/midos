package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimpleFallbackPlan implements FallbackPlan {

    @Override
    public List<String> fallbacks(String primary) {
        return List.of(); // placeholder: configured fallbacks can be added later
    }
}
