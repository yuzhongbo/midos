package com.zhongbo.mindos.assistant.memory.model;

public enum LongTaskStatus {
    PENDING,
    RUNNING,
    BLOCKED,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}

