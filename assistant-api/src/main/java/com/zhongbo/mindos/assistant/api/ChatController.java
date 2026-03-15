package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.ChatRequestDto;
import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/chat", "/api/chat"})
public class ChatController {

    private final DispatcherService dispatcherService;
    private final MemoryManager memoryManager;

    public ChatController(DispatcherService dispatcherService, MemoryManager memoryManager) {
        this.dispatcherService = dispatcherService;
        this.memoryManager = memoryManager;
    }

    /**
     * Example response:
     * {"reply":"hello","channel":"echo"}
     */
    @PostMapping
    public ChatResponseDto chat(@RequestBody ChatRequestDto request) {
        Map<String, Object> profileContext = new LinkedHashMap<>();
        if (request.profile() != null) {
            putIfNotNull(profileContext, "assistantName", request.profile().assistantName());
            putIfNotNull(profileContext, "role", request.profile().role());
            putIfNotNull(profileContext, "style", request.profile().style());
            putIfNotNull(profileContext, "language", request.profile().language());
            putIfNotNull(profileContext, "timezone", request.profile().timezone());
            putIfNotNull(profileContext, "llmProvider", request.profile().llmProvider());
        }
        DispatchResult result = dispatcherService.dispatch(request.userId(), request.message(), profileContext);
        return new ChatResponseDto(result.reply(), result.channel());
    }

    @GetMapping("/{userId}/history")
    public List<ConversationTurn> history(@PathVariable String userId) {
        return memoryManager.getConversation(userId);
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}

