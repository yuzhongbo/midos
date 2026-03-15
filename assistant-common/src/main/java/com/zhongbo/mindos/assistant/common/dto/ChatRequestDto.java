package com.zhongbo.mindos.assistant.common.dto;

public record ChatRequestDto(String userId, String message, AssistantProfileDto profile) {

	public ChatRequestDto(String userId, String message) {
		this(userId, message, null);
	}
}

