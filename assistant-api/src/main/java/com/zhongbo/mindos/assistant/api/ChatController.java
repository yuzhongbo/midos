package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.ChatRequestDto;
import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping({"/chat", "/api/chat"})
public class ChatController {

    private final DispatcherService dispatcherService;
    private final MemoryFacade memoryFacade;

    public ChatController(DispatcherService dispatcherService, MemoryFacade memoryFacade) {
        this.dispatcherService = dispatcherService;
        this.memoryFacade = memoryFacade;
    }

    /**
     * Example response:
     * {"reply":"hello","channel":"echo"}
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponseDto chat(@RequestBody ChatRequestDto request) {
        Map<String, Object> profileContext = buildProfileContext(request);
        DispatchResult result = dispatcherService.dispatch(request.userId(), request.message(), profileContext);
        return new ChatResponseDto(result.reply(), result.channel(), result.executionTrace());
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequestDto request) {
        SseEmitter emitter = new SseEmitter(0L);
        Map<String, Object> profileContext = buildProfileContext(request);

        sendSse(emitter, "start", Map.of(
                "userId", request.userId(),
                "stream", true
        ));

        CompletableFuture<DispatchResult> future = dispatcherService.dispatchStream(
                request.userId(),
                request.message(),
                profileContext,
                chunk -> sendSse(emitter, "delta", Map.of("text", chunk))
        );
        future.whenComplete((result, error) -> {
            if (error != null) {
                sendSse(emitter, "error", Map.of("message", error.getMessage() == null ? "stream_failed" : error.getMessage()));
                emitter.completeWithError(error);
                return;
            }
            sendSse(emitter, "done", Map.of(
                    "reply", result.reply(),
                    "channel", result.channel()
            ));
            emitter.complete();
        });
        return emitter;
    }

    @GetMapping("/{userId}/history")
    public List<ConversationTurn> history(@PathVariable String userId) {
        return memoryFacade.getConversation(userId);
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> buildProfileContext(ChatRequestDto request) {
        Map<String, Object> profileContext = new LinkedHashMap<>();
        if (request.profile() != null) {
            putIfNotNull(profileContext, "assistantName", request.profile().assistantName());
            putIfNotNull(profileContext, "role", request.profile().role());
            putIfNotNull(profileContext, "style", request.profile().style());
            putIfNotNull(profileContext, "language", request.profile().language());
            putIfNotNull(profileContext, "timezone", request.profile().timezone());
            putIfNotNull(profileContext, "llmProvider", request.profile().llmProvider());
            putIfNotNull(profileContext, "llmPreset", request.profile().llmPreset());
        }
        return profileContext;
    }

    private void sendSse(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }
}
