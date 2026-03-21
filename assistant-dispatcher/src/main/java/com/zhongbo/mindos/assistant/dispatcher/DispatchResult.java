package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;

public record DispatchResult(String reply, String channel, ExecutionTraceDto executionTrace) {

	public DispatchResult(String reply, String channel) {
		this(reply, channel, null);
	}
}

