package com.zhongbo.mindos.assistant.common.dto;

public record ChatResponseDto(String reply, String channel, ExecutionTraceDto executionTrace) {

	public ChatResponseDto(String reply, String channel) {
		this(reply, channel, null);
	}
}

