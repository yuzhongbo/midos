package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.nlu.MemoryIntentNlu;
import com.zhongbo.mindos.assistant.dispatcher.DispatchResult;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ImGatewayService {

    private final DispatcherService dispatcherService;
    private final MemoryManager memoryManager;

    ImGatewayService(DispatcherService dispatcherService,
                     MemoryManager memoryManager) {
        this.dispatcherService = dispatcherService;
        this.memoryManager = memoryManager;
    }

    String chat(ImPlatform platform, String senderId, String chatId, String text) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            return "请发送文本消息，我会继续协助你。";
        }

        String userId = buildUserId(platform, senderId);
        Map<String, Object> profileContext = new LinkedHashMap<>();
        profileContext.put("imPlatform", platform.name().toLowerCase());
        profileContext.put("imSenderId", senderId == null ? "" : senderId);
        profileContext.put("imChatId", chatId == null ? "" : chatId);

        String memoryReply = tryHandleMemoryPlanningIntent(userId, normalizedText);
        if (memoryReply != null) {
            return memoryReply;
        }

        DispatchResult result = dispatcherService.dispatch(userId, normalizedText, profileContext);
        return result.reply();
    }

    private String tryHandleMemoryPlanningIntent(String userId, String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.trim();
        String sample = MemoryIntentNlu.extractAutoTuneSample(normalized);
        if (sample != null || normalized.contains("自动微调记忆风格") || normalized.contains("微调记忆风格")) {
            MemoryStyleProfile updated = memoryManager.updateMemoryStyleProfile(
                    userId,
                    new MemoryStyleProfile(null, null, null),
                    true,
                    sample == null ? normalized : sample
            );
            return "记忆风格已微调: " + updated.styleName()
                    + "，语气=" + updated.tone()
                    + "，格式=" + updated.outputFormat();
        }
        MemoryIntentNlu.StyleUpdateIntent styleUpdateIntent = MemoryIntentNlu.extractStyleUpdateIntent(normalized);
        if (styleUpdateIntent != null && styleUpdateIntent.hasValues()) {
            MemoryStyleProfile updated = memoryManager.updateMemoryStyleProfile(userId,
                    new MemoryStyleProfile(styleUpdateIntent.styleName(), styleUpdateIntent.tone(), styleUpdateIntent.outputFormat()));
            return "记忆风格已更新: " + updated.styleName()
                    + "，语气=" + updated.tone()
                    + "，格式=" + updated.outputFormat();
        }
        if (MemoryIntentNlu.isStyleShowIntent(normalized)) {
            MemoryStyleProfile style = memoryManager.getMemoryStyleProfile(userId);
            return "当前记忆风格: " + style.styleName()
                    + "，语气=" + style.tone()
                    + "，格式=" + style.outputFormat();
        }

        MemoryIntentNlu.CompressionIntent compressionIntent = MemoryIntentNlu.extractCompressionIntent(normalized);
        if (compressionIntent == null) {
            return null;
        }

        String source = compressionIntent.source() == null || compressionIntent.source().isBlank()
                ? normalized
                : compressionIntent.source();
        String focus = compressionIntent.focus();

        MemoryCompressionPlan plan = memoryManager.buildMemoryCompressionPlan(
                userId,
                source,
                new MemoryStyleProfile(null, null, null),
                focus
        );
        return plan.steps().stream()
                .filter(step -> "STYLED".equals(step.stage()))
                .map(MemoryCompressionStep::content)
                .findFirst()
                .orElse("已生成记忆压缩规划。");
    }


    private String buildUserId(ImPlatform platform, String senderId) {
        String normalizedSender = senderId == null || senderId.isBlank() ? "anonymous" : senderId.trim();
        return "im:" + platform.name().toLowerCase() + ":" + normalizedSender;
    }
}

