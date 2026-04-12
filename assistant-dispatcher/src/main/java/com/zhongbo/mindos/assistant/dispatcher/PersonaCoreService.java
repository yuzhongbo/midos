package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PersonaCoreService {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final boolean enabled;
    private final int preferredChannelMinConsecutiveSuccess;
    private final Set<String> ignoredProfileTerms;

    public PersonaCoreService(MemoryFacade memoryFacade,
                              boolean enabled,
                              int preferredChannelMinConsecutiveSuccess,
                              String ignoredProfileTerms) {
        this(new DispatcherMemoryFacade(memoryFacade), null, enabled, preferredChannelMinConsecutiveSuccess, ignoredProfileTerms);
    }

    @Autowired
    public PersonaCoreService(DispatcherMemoryFacade dispatcherMemoryFacade,
                               com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService memoryCommandService,
                              @Value("${mindos.dispatcher.persona-core.enabled:true}") boolean enabled,
                              @Value("${mindos.dispatcher.persona-core.preferred-channel.min-consecutive-success:2}") int preferredChannelMinConsecutiveSuccess,
                              @Value("${mindos.dispatcher.persona-core.ignored-profile-terms:unknown,null,n/a,na,tbd,todo,随便,不知道,待定}") String ignoredProfileTerms) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.enabled = enabled;
        this.preferredChannelMinConsecutiveSuccess = Math.max(1, preferredChannelMinConsecutiveSuccess);
        this.ignoredProfileTerms = parseIgnoredTerms(ignoredProfileTerms);
    }

    public Map<String, Object> resolveProfileContext(String userId, Map<String, Object> transientProfileContext) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (transientProfileContext != null) {
            merged.putAll(transientProfileContext);
        }
        if (!enabled) {
            return merged;
        }

        PreferenceProfile saved = dispatcherMemoryFacade.getPreferenceProfile(userId);
        putIfAbsent(merged, "assistantName", saved.assistantName());
        putIfAbsent(merged, "role", saved.role());
        putIfAbsent(merged, "style", saved.style());
        putIfAbsent(merged, "language", saved.language());
        putIfAbsent(merged, "timezone", saved.timezone());
        return merged;
    }

    public MemoryWriteBatch learnFromTurn(String userId,
                                          Map<String, Object> profileContext,
                                          SkillResult result) {
        if (!enabled) {
            return MemoryWriteBatch.empty();
        }
        Map<String, Object> safeProfileContext = profileContext == null ? Map.of() : profileContext;
        PreferenceProfile incoming = new PreferenceProfile(
                sanitizeLearnedValue(asText(safeProfileContext.get("assistantName"))),
                sanitizeLearnedValue(asText(safeProfileContext.get("role"))),
                sanitizeLearnedValue(asText(safeProfileContext.get("style"))),
                sanitizeLearnedValue(asText(safeProfileContext.get("language"))),
                sanitizeLearnedValue(asText(safeProfileContext.get("timezone"))),
                resolveLearnedPreferredChannel(userId, result)
        );
        if (incoming.equals(PreferenceProfile.empty())) {
            return MemoryWriteBatch.empty();
        }
        return MemoryWriteBatch.of(new MemoryWriteOperation.UpdatePreferenceProfile(incoming));
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

    private String sanitizeLearnedValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            return null;
        }
        String canonical = normalized.toLowerCase(Locale.ROOT);
        return ignoredProfileTerms.contains(canonical) ? null : normalized;
    }

    private String resolveLearnedPreferredChannel(String userId, SkillResult result) {
        if (result == null || !result.success()) {
            return null;
        }
        String channel = sanitizeLearnedValue(result.skillName());
        if (channel == null) {
            return null;
        }
        int consecutiveSuccess = countConsecutiveSkillSuccess(userId, channel);
        return consecutiveSuccess >= preferredChannelMinConsecutiveSuccess ? channel : null;
    }

    private int countConsecutiveSkillSuccess(String userId, String skillName) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        int streak = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (!entry.success() || !skillName.equals(entry.skillName())) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private Set<String> parseIgnoredTerms(String rawTerms) {
        if (rawTerms == null || rawTerms.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String token : rawTerms.split(",")) {
            String normalized = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                parsed.add(normalized);
            }
        }
        return parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
    }
}
