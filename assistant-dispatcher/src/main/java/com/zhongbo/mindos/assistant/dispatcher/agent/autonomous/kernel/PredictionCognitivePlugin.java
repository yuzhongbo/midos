package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PredictionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldModel;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PredictionCognitivePlugin implements CognitivePlugin {

    private final WorldModel worldModel;

    public PredictionCognitivePlugin(WorldModel worldModel) {
        this.worldModel = worldModel;
    }

    @Override
    public String pluginId() {
        return "prediction.world-model";
    }

    @Override
    public CognitiveCapability capability() {
        return CognitiveCapability.PREDICTION;
    }

    @Override
    public RuntimeObject runtimeObject() {
        return new RuntimeObject(
                "plugin.prediction.world-model",
                RuntimeObjectType.COGNITIVE_PLUGIN,
                "prediction",
                Map.of("predictor", "world-model")
        );
    }

    @Override
    public CognitivePluginOutput run(CognitivePluginContext context) {
        Task task = context == null ? null : context.task();
        RuntimeState state = context == null ? null : context.runtimeState();
        TaskGraph graph = task == null ? null : task.graph();
        if ((graph == null || graph.isEmpty()) && state != null && state.plan() != null) {
            graph = state.plan().graph();
        }
        if (graph == null || graph.isEmpty()) {
            return CognitivePluginOutput.empty();
        }
        RuntimeContext runtimeContext = state == null ? RuntimeContext.empty() : state.context();
        AutonomousPlanningContext planningContext = new AutonomousPlanningContext(
                runtimeContext.userId(),
                runtimeContext.input(),
                runtimeContext.attributes(),
                null,
                state == null ? 1 : state.pointer().cycle(),
                state == null ? null : state.lastResult(),
                state == null ? null : state.lastEvaluation(),
                state == null || state.pointer() == null ? java.util.List.of() : state.pointer().failedTargets()
        );
        PredictionResult prediction = worldModel == null
                ? new PredictionResult(0.0, 1.0, 1.0, 1.0)
                : worldModel.predict(graph, planningContext);
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("prediction.successProbability", prediction.successProbability());
        attributes.put("prediction.cost", prediction.cost());
        attributes.put("prediction.latency", prediction.latency());
        attributes.put("prediction.risk", prediction.risk());
        return new CognitivePluginOutput(
                null,
                attributes,
                prediction.successProbability(),
                "predicted success=" + prediction.successProbability()
        );
    }

    @Override
    public int priority() {
        return 8;
    }
}
