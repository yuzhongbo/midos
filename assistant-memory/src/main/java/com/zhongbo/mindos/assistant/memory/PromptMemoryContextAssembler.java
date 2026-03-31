package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;

import java.util.Map;

public interface PromptMemoryContextAssembler {

    PromptMemoryContextDto assemble(String userId, String query, int maxChars, Map<String, Object> profileContext);
}

