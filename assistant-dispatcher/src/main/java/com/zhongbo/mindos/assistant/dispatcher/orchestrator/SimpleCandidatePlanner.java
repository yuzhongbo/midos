package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SimpleCandidatePlanner extends AdaptiveCandidatePlanner {

    public SimpleCandidatePlanner() {
        super();
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        super(skillEngine, memoryGateway, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        super(skillEngine, memoryGateway, graphMemory, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  SkillCostTelemetry skillCostTelemetry,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        this(skillEngine, memoryGateway, graphMemory, skillCostTelemetry, null, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  SkillCostTelemetry skillCostTelemetry,
                                  PlannerLearningStore plannerLearningStore,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        super(skillEngine, memoryGateway, graphMemory, skillCostTelemetry, plannerLearningStore, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight, 0.10, 0.18, 8.0);
    }

    @Autowired
    public SimpleCandidatePlanner(SkillEngine skillEngine,
                                  MemoryGateway memoryGateway,
                                  GraphMemory graphMemory,
                                  ObjectProvider<SkillCostTelemetry> skillCostTelemetryProvider,
                                  ObjectProvider<PlannerLearningStore> plannerLearningStoreProvider,
                                  @Value("${mindos.dispatcher.candidate-planner.max-candidates:3}") int maxCandidates,
                                  @Value("${mindos.dispatcher.candidate-planner.explicit-weight:0.40}") double explicitWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.keyword-weight:0.35}") double keywordWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.memory-weight:0.15}") double memoryWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.success-weight:0.10}") double successWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.latency-weight:0.10}") double latencyWeight,
                                  @Value("${mindos.dispatcher.candidate-planner.learning-rate:0.18}") double learningRate,
                                  @Value("${mindos.dispatcher.candidate-planner.memory-saturation:8.0}") double memorySaturation) {
        super(skillEngine,
                memoryGateway,
                graphMemory,
                skillCostTelemetryProvider == null ? null : skillCostTelemetryProvider.getIfAvailable(),
                plannerLearningStoreProvider == null ? null : plannerLearningStoreProvider.getIfAvailable(),
                maxCandidates,
                explicitWeight,
                keywordWeight,
                memoryWeight,
                successWeight,
                latencyWeight,
                learningRate,
                memorySaturation);
    }
}
