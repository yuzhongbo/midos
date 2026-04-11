package com.zhongbo.mindos.assistant.dispatcher.agent.network;

import java.util.Optional;

final class AgentNetworkBroker {

    private static final AgentNetworkBroker INSTANCE = new AgentNetworkBroker();

    private final java.util.concurrent.ConcurrentMap<String, java.util.Deque<AgentMessage>> mailboxes = new java.util.concurrent.ConcurrentHashMap<>();

    static AgentNetworkBroker shared() {
        return INSTANCE;
    }

    void publish(String mailbox, AgentMessage message) {
        String safeMailbox = normalize(mailbox);
        if (safeMailbox.isBlank() || message == null) {
            return;
        }
        mailboxes.computeIfAbsent(safeMailbox, ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>()).addLast(message);
    }

    Optional<AgentMessage> poll(String mailbox) {
        String safeMailbox = normalize(mailbox);
        if (safeMailbox.isBlank()) {
            return Optional.empty();
        }
        java.util.Deque<AgentMessage> deque = mailboxes.get(safeMailbox);
        if (deque == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(deque.pollFirst());
    }

    void clear() {
        mailboxes.clear();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
