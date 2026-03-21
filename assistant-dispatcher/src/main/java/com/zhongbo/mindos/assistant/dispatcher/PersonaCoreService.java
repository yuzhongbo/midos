package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PersonaCoreService {

    private final MemoryManager memoryManager;
    private final boolean enabled;

    public PersonaCoreService(MemoryManager memoryManager,
                              @Value("${mindos.dispatcher.persona-core.enabled:true}") boolean enabled) {
        this.memoryManager = memoryManager;
        this.enabled = enabled;
    }

    public Map<String, Object> resolveProfileContext(String userId, Map<String, Object> transientProfileContext) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (transientProfileContext != null) {
            merged.putAll(transientProfileContext);
        }
        if (!enabled) {
            return merged;
        }

        PreferenceProfile saved = memoryManager.getPreferenceProfile(userId);
        putIfAbsent(merged, "assistantName", saved.assistantName());
        putIfAbsent(merged, "role", saved.role());
        putIfAbsent(merged, "style", saved.style());
        putIfAbsent(merged, "language", saved.language());
        putIfAbsent(merged, "timezone", saved.timezone());
        return merged;
    }

    public void learnFromTurn(String userId,
                              Map<String, Object> profileContext,
                              SkillResult result) {
        if (!enabled) {
            return;
        }
        PreferenceProfile incoming = new PreferenceProfile(
                asText(profileContext.get("assistantName")),
                asText(profileContext.get("role")),
                asText(profileContext.get("style")),
                asText(profileContext.get("language")),
                asText(profileContext.get("timezone")),
                result == null ? null : result.skillName()
        );
        memoryManager.updatePreferenceProfile(userId, incoming);
    }

    private void putIfAbsent(Map<String, Object> target, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.containsKey(key) || target.get(key) == null) {
            target.put(key, value);
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

