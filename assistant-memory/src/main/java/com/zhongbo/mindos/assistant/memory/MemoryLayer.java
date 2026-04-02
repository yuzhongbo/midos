package com.zhongbo.mindos.assistant.memory;

/**
 * Buffer is currently backed by recent conversation, while the remaining layers
 * classify semantic memory for ranking and prompt budgeting.
 */
public enum MemoryLayer {
    BUFFER,
    WORKING,
    SEMANTIC,
    FACT
}
