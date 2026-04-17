package com.zhongbo.mindos.assistant.memory.model;

public enum LongGoalStatus {
    ACTIVE,
    ACHIEVED,
    ON_HOLD,
    CANCELLED;

    public boolean isTerminal() {
        return this == ACHIEVED || this == CANCELLED;
    }
}
