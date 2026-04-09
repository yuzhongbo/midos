package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.List;

public interface FallbackPlan {

    List<String> fallbacks(String primary);
}
