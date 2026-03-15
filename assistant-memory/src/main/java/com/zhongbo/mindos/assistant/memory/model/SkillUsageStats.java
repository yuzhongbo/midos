package com.zhongbo.mindos.assistant.memory.model;

public record SkillUsageStats(String skillName, long totalCount, long successCount, long failureCount) {
}

