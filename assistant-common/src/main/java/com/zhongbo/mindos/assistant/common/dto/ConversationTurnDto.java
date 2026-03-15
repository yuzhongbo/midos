package com.zhongbo.mindos.assistant.common.dto;

import java.time.Instant;

public record ConversationTurnDto(String role, String content, Instant createdAt) {
}

