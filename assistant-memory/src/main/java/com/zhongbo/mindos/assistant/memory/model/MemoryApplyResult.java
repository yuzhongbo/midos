package com.zhongbo.mindos.assistant.memory.model;

public record MemoryApplyResult(
		long cursor,
		int acceptedCount,
		int skippedCount,
		int deduplicatedCount,
		int keySignalInputCount,
		int keySignalStoredCount
) {
}

