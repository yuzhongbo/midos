package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

public record TaskHandle(String taskId) {

    public TaskHandle {
        taskId = taskId == null ? "" : taskId.trim();
    }

    public boolean isEmpty() {
        return taskId.isBlank();
    }
}
