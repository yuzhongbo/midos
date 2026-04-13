package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

public enum GoalStatus {
    ACTIVE,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
