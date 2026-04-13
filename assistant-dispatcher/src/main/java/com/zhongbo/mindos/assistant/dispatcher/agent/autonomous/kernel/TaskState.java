package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

public enum TaskState {
    INIT,
    RUNNING,
    WAITING,
    SUSPENDED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
