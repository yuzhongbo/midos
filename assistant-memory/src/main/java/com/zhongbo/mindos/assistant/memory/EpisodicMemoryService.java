package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EpisodicMemoryService {

    private final Map<String, List<ConversationTurn>> historyByUser = new ConcurrentHashMap<>();

    public void appendUserMessage(String userId, String message) {
        append(userId, ConversationTurn.user(message));
    }

    public void appendAssistantMessage(String userId, String message) {
        append(userId, ConversationTurn.assistant(message));
    }

    public void appendTurn(String userId, ConversationTurn turn) {
        append(userId, turn);
    }

    public List<ConversationTurn> getConversation(String userId) {
        return List.copyOf(historyByUser.getOrDefault(userId, List.of()));
    }

    public List<ConversationTurn> getRecentConversation(String userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ConversationTurn> conversation = historyByUser.getOrDefault(userId, List.of());
        int start = Math.max(0, conversation.size() - limit);
        return List.copyOf(conversation.subList(start, conversation.size()));
    }

    public void replaceConversation(String userId, List<ConversationTurn> turns) {
        historyByUser.put(userId, new ArrayList<>(turns == null ? List.of() : turns));
    }

    private void append(String userId, ConversationTurn turn) {
        historyByUser.computeIfAbsent(userId, key -> new ArrayList<>()).add(turn);
    }
}

