package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimpleCandidatePlanner implements CandidatePlanner {

    @Override
    public List<String> plan(String suggestedTarget) {
        if (suggestedTarget == null || suggestedTarget.isBlank()) {
            return List.of();
        }
        return List.of(suggestedTarget);
    }
}
