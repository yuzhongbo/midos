package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

@Component
public class PlanEvaluator {

    public PlanScore evaluate(TaskGraph graph, PredictionResult prediction) {
        PredictionResult safePrediction = prediction == null ? new PredictionResult(0.0, 1.0, 1.0, 1.0) : prediction;
        double efficiencyScore = clamp(1.0 - (safePrediction.cost() * 0.6 + safePrediction.latency() * 0.4));
        double score = safePrediction.successProbability() * 0.5
                + (1.0 - safePrediction.risk()) * 0.3
                + efficiencyScore * 0.2;
        return new PlanScore(score, efficiencyScore);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
