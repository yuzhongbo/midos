package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

public record TaskEdge(String from, String to) {

    public TaskEdge {
        from = from == null ? "" : from.trim();
        to = to == null ? "" : to.trim();
    }
}
