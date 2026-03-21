package com.zhongbo.mindos.assistant.memory.model;

import java.util.List;

public record PreferenceProfileExplain(
        PreferenceProfile confirmedProfile,
        List<PendingPreferenceOverride> pendingOverrides
) {
}

