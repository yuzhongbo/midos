package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

public record Resource(String ownerId, ResourceType type) {

    public Resource {
        ownerId = ownerId == null ? "" : ownerId.trim();
        type = type == null ? ResourceType.COMPUTE : type;
    }
}
