package com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory;

import java.util.ArrayList;
import java.util.List;

public record MemoryWriteBatch(List<MemoryWriteOperation> operations) {

    public MemoryWriteBatch {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public static MemoryWriteBatch empty() {
        return new MemoryWriteBatch(List.of());
    }

    public static MemoryWriteBatch of(MemoryWriteOperation... operations) {
        if (operations == null || operations.length == 0) {
            return empty();
        }
        List<MemoryWriteOperation> values = new ArrayList<>();
        for (MemoryWriteOperation operation : operations) {
            if (operation != null) {
                values.add(operation);
            }
        }
        return values.isEmpty() ? empty() : new MemoryWriteBatch(values);
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public MemoryWriteBatch merge(MemoryWriteBatch other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        List<MemoryWriteOperation> merged = new ArrayList<>(operations.size() + other.operations.size());
        merged.addAll(operations);
        merged.addAll(other.operations);
        return new MemoryWriteBatch(merged);
    }

    public MemoryWriteBatch append(MemoryWriteOperation operation) {
        if (operation == null) {
            return this;
        }
        List<MemoryWriteOperation> merged = new ArrayList<>(operations.size() + 1);
        merged.addAll(operations);
        merged.add(operation);
        return new MemoryWriteBatch(merged);
    }
}
