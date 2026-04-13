package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public interface AgentLoop {

    AutonomousCycleResult runOnce(AutonomousLoopRequest request);

    AutonomousLoopResult runUntilStopped(AutonomousLoopRequest request, AtomicBoolean stopSignal);

    default AutonomousGoalRunResult runGoal(String userId,
                                            String goalDescription,
                                            Map<String, Object> profileContext) {
        throw new UnsupportedOperationException("explicit goal execution is not configured");
    }

    default AutonomousLoopResult runForCycles(AutonomousLoopRequest request, int cycles) {
        AutonomousLoopRequest bounded = request == null ? AutonomousLoopRequest.of("") : request.withMaxCycles(cycles);
        return runUntilStopped(bounded, new AtomicBoolean(false));
    }
}
