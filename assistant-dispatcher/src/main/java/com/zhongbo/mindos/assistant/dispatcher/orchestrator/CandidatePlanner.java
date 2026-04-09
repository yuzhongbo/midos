package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.List;

public interface CandidatePlanner {

    List<String> plan(String suggestedTarget);
}
