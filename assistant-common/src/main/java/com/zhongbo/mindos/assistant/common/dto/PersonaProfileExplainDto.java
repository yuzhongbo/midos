package com.zhongbo.mindos.assistant.common.dto;

import java.util.List;

public record PersonaProfileExplainDto(
        PersonaProfileDto confirmed,
        List<PendingPreferenceOverrideDto> pendingOverrides
) {
}

