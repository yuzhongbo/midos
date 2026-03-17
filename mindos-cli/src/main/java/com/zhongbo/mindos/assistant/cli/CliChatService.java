package com.zhongbo.mindos.assistant.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.dto.AssistantProfileDto;
import com.zhongbo.mindos.assistant.common.dto.ChatRequestDto;
import com.zhongbo.mindos.assistant.common.dto.ChatResponseDto;
import com.zhongbo.mindos.assistant.common.dto.ConversationTurnDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryCompressionPlanResponseDto;
import com.zhongbo.mindos.assistant.common.dto.MemoryStyleProfileDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncRequestDto;
import com.zhongbo.mindos.assistant.common.dto.MemorySyncResponseDto;
import com.zhongbo.mindos.assistant.sdk.AssistantSdkClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class CliChatService {

    private String userId;
    private String server;
    private final Path profileConfig;
    private String sessionLlmProviderOverride;
    private final AssistantProfileStore profileStore = new AssistantProfileStore();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    CliChatService(String userId, String server, Path profileConfig, String llmProviderOverride) {
        this.userId = userId;
        this.server = UrlSecurityPolicy.requireAllowedSensitiveUrl(server, "server");
        this.profileConfig = profileConfig;
        this.sessionLlmProviderOverride = llmProviderOverride;
    }

    ChatResponseDto sendMessage(String message) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.chat(new ChatRequestDto(userId, message, buildProfileDto()));
    }

    MemorySyncResponseDto pullMemory(long since, int limit) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.fetchMemorySync(userId, since, limit);
    }

    MemorySyncResponseDto pushMemory(Path file, int limit) throws IOException {
        MemorySyncRequestDto request = objectMapper.readValue(file.toFile(), MemorySyncRequestDto.class);
        return pushMemory(request, limit);
    }

    MemorySyncResponseDto pushMemory(MemorySyncRequestDto request, int limit) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.applyMemorySync(userId, request, limit);
    }

    MemoryStyleProfileDto getMemoryStyleProfile() {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.getMemoryStyle(userId);
    }

    MemoryStyleProfileDto updateMemoryStyleProfile(MemoryStyleProfileDto request) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.updateMemoryStyle(userId, request);
    }

    MemoryCompressionPlanResponseDto buildMemoryCompressionPlan(MemoryCompressionPlanRequestDto request) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.buildMemoryCompressionPlan(userId, request);
    }

    List<ConversationTurnDto> fetchConversationHistory() {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.fetchConversationHistory(userId);
    }

    List<Map<String, String>> listSkills() {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.listSkills();
    }

    Map<String, Object> reloadSkills() {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.reloadSkills();
    }

    Map<String, Object> reloadMcpSkills() {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.reloadMcpSkills();
    }

    Map<String, Object> loadMcpServer(String alias, String url) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.loadMcpServer(alias, url);
    }

    Map<String, Object> loadExternalJar(String url) {
        AssistantSdkClient client = new AssistantSdkClient(java.net.URI.create(server));
        return client.loadExternalJar(url);
    }

    AssistantProfile loadProfile() {
        try {
            return profileStore.loadOrDefault(profileConfig);
        } catch (RuntimeException ex) {
            return profileStore.defaultProfile();
        }
    }

    AssistantProfileDto buildProfileDto() {
        AssistantProfile profile = loadProfile();
        String resolvedProvider = resolvedLlmProvider();
        return new AssistantProfileDto(
                profile.assistantName(),
                profile.role(),
                profile.style(),
                profile.language(),
                profile.timezone(),
                resolvedProvider
        );
    }

    String userId() {
        return userId;
    }

    void setUserId(String userId) {
        this.userId = userId;
    }

    String server() {
        return server;
    }

    void setServer(String server) {
        this.server = UrlSecurityPolicy.requireAllowedSensitiveUrl(server, "server");
    }

    String assistantName() {
        return loadProfile().assistantName();
    }

    String resolvedLlmProvider() {
        if (sessionLlmProviderOverride != null && !sessionLlmProviderOverride.isBlank()) {
            return sessionLlmProviderOverride.trim();
        }
        String profileProvider = loadProfile().llmProvider();
        if (profileProvider == null || profileProvider.isBlank()) {
            return "(default)";
        }
        return profileProvider;
    }

    void setSessionLlmProvider(String llmProvider) {
        this.sessionLlmProviderOverride = llmProvider == null ? null : llmProvider.trim();
    }

    void clearSessionLlmProvider() {
        this.sessionLlmProviderOverride = null;
    }

    Path profileConfig() {
        return profileConfig;
    }

    void saveProfile(AssistantProfile profile) {
        profileStore.save(profileConfig, profile);
    }

    void resetProfile() {
        profileStore.save(profileConfig, profileStore.defaultProfile());
    }

    AssistantProfile updateProfile(String name,
                                   String role,
                                   String style,
                                   String language,
                                   String timezone,
                                   String llmProvider) {
        AssistantProfile current = loadProfile();
        String nextName = name == null ? current.assistantName() : name.trim();
        String nextRole = role == null ? current.role() : role.trim();
        String nextStyle = style == null ? current.style() : style.trim();
        String nextLanguage = language == null ? current.language() : language.trim();
        String nextTimezone = timezone == null ? current.timezone() : timezone.trim();
        String nextProvider = llmProvider == null ? current.llmProvider() : llmProvider.trim();

        ProfileInputValidator.requireNotBlankValue(nextName, "name");
        ProfileInputValidator.requireNotBlankValue(nextRole, "role");
        ProfileInputValidator.requireNotBlankValue(nextStyle, "style");
        ProfileInputValidator.validateLanguageValue(nextLanguage);
        ProfileInputValidator.validateTimezoneValue(nextTimezone);

        AssistantProfile updated = new AssistantProfile(
                nextName,
                nextRole,
                nextStyle,
                nextLanguage,
                nextTimezone,
                nextProvider
        );
        saveProfile(updated);
        return updated;
    }
}

