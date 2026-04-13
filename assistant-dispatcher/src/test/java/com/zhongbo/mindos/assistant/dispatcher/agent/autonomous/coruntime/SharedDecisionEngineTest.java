package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.AGIMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.CognitiveModule;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPlan;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.ExecutionPolicy;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Node;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeState;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeResourceType;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.Task;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.TaskHandle;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel.RuntimeObject;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedDecisionEngineTest {

    @Test
    void shouldRequireHumanReviewForHighRiskPlans() {
        AGIMemory memory = new AGIMemory();
        HumanRuntimeSessionManager sessionManager = new HumanRuntimeSessionManager();
        try {
            sessionManager.activate(HumanRuntimeSession.from("u-risk", Goal.of("发布生产环境", 1.0), Map.of()));
            SharedDecisionEngine engine = decisionEngine(memory, sessionManager);
            Task task = new Task(
                    "task-risk",
                    Goal.of("发布生产环境", 1.0),
                    planGraph("deploy.prod"),
                    ExecutionPolicy.AUTONOMOUS,
                    Map.of("userId", "u-risk", "coruntime.shared", true)
            );

            SharedDecision decision = engine.decide(task, SharedDecisionContext.from(
                    new TaskHandle(task.taskId()),
                    RuntimeState.initial(task),
                    plan(task, Map.of(
                            "prediction.successProbability", 0.34,
                            "prediction.risk", 0.82,
                            "prediction.cost", 0.88,
                            "tool.targets", List.of("deploy.prod")
                    )),
                    null,
                    null,
                    -1.0
            ));

            assertTrue(decision.requiresHumanApproval());
            assertFalse(decision.allowExecution());
            assertEquals(DecisionMode.JOINT_REVIEW, decision.mode());
            assertFalse(decision.explanation().summary().isBlank());
            assertTrue(decision.explanation().risks().stream().anyMatch(risk -> risk.contains("predicted-risk")));
        } finally {
            sessionManager.clear();
        }
    }

    @Test
    void shouldAllowAutonomousExecutionForTrustedLowRiskPlans() {
        AGIMemory memory = new AGIMemory();
        HumanRuntimeSessionManager sessionManager = new HumanRuntimeSessionManager();
        try {
            sessionManager.activate(HumanRuntimeSession.from("u-auto", Goal.of("总结日报", 0.4), Map.of(
                    "human.preference.autonomy", 0.9,
                    "human.preference.riskTolerance", 0.7
            )));
            SharedDecisionEngine engine = decisionEngine(memory, sessionManager);
            Task task = new Task(
                    "task-auto",
                    Goal.of("总结日报", 0.4),
                    planGraph("notes.summarize"),
                    ExecutionPolicy.AUTONOMOUS,
                    Map.of("userId", "u-auto", "coruntime.shared", true)
            );

            SharedDecision decision = engine.decide(task, SharedDecisionContext.from(
                    new TaskHandle(task.taskId()),
                    RuntimeState.initial(task),
                    plan(task, Map.of(
                            "prediction.successProbability", 0.92,
                            "prediction.risk", 0.12,
                            "prediction.cost", 0.18,
                            "tool.targets", List.of("notes.summarize")
                    )),
                    null,
                    null,
                    -1.0
            ));

            assertTrue(decision.allowExecution());
            assertEquals(DecisionMode.AI_AUTONOMOUS, decision.mode());
            assertFalse(decision.explanation().summary().isBlank());
            assertTrue(decision.explanation().reasons().stream().anyMatch(reason -> reason.contains("trust")));
        } finally {
            sessionManager.clear();
        }
    }

    private SharedDecisionEngine decisionEngine(AGIMemory memory, HumanRuntimeSessionManager sessionManager) {
        RuntimeHumanInterface humanInterface = new RuntimeHumanInterface(sessionManager);
        HumanPreferenceModel preferenceModel = new HumanPreferenceModel(memory);
        TrustModel trustModel = new TrustModel(memory);
        return new SharedDecisionEngine(
                new ControlProtocol(),
                humanInterface,
                new ExplainabilityEngine(),
                preferenceModel,
                trustModel,
                memory
        );
    }

    private ExecutionPlan plan(Task task, Map<String, Object> attributes) {
        return new ExecutionPlan(
                task,
                task.graph(),
                task.policy(),
                new CognitiveModule(null, null, null, null, null),
                Map.of(),
                List.<RuntimeObject>of(),
                Map.<RuntimeResourceType, Double>of(),
                Node.local(),
                attributes,
                "plan",
                Instant.now()
        );
    }

    private TaskGraph planGraph(String target) {
        return new TaskGraph(List.of(
                new TaskNode("task-1", target, Map.of(), List.of(), "result", false, 1)
        ));
    }
}
