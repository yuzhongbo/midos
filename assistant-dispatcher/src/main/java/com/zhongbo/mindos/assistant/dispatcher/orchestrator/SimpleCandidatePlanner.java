package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SimpleCandidatePlanner extends AdaptiveCandidatePlanner {

    public SimpleCandidatePlanner() {
        super();
    }

    public SimpleCandidatePlanner(SkillCatalogFacade skillEngine,
                                  DispatcherMemoryFacade dispatcherMemoryFacade,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        super(skillEngine, dispatcherMemoryFacade, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillCatalogFacade skillEngine,
                                  DispatcherMemoryFacade dispatcherMemoryFacade,
                                  SkillCostTelemetry skillCostTelemetry,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        this(skillEngine, dispatcherMemoryFacade, skillCostTelemetry, null, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight);
    }

    public SimpleCandidatePlanner(SkillCatalogFacade skillEngine,
                                  DispatcherMemoryFacade dispatcherMemoryFacade,
                                  SkillCostTelemetry skillCostTelemetry,
                                  PlannerLearningStore plannerLearningStore,
                                  int maxCandidates,
                                  double explicitWeight,
                                  double keywordWeight,
                                  double memoryWeight,
                                  double successWeight) {
        super(skillEngine, dispatcherMemoryFacade, skillCostTelemetry, plannerLearningStore, maxCandidates, explicitWeight, keywordWeight, memoryWeight, successWeight, 0.10, 0.18, 8.0);
    }

    @Autowired
    public SimpleCandidatePlanner(SkillCatalogFacade skillEngine,
                                  DispatcherMemoryFacade dispatcherMemoryFacade,
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
                dispatcherMemoryFacade,
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
