package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;

public record QueryContext(
        String userId,
        String userQuery,
        PromptMemoryContextDto promptMemoryContext,
        boolean explicitLlmRequest,
        boolean complexReasoningRequired
) {
}
