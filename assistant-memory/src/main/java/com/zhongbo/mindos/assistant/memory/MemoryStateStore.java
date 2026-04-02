package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.function.Supplier;

public interface MemoryStateStore {

    <T> T readState(String fileName, TypeReference<T> typeReference, Supplier<T> fallbackSupplier);

    void writeState(String fileName, Object value);

    static MemoryStateStore noOp() {
        return NoOpMemoryStateStore.INSTANCE;
    }

    final class NoOpMemoryStateStore implements MemoryStateStore {
        private static final NoOpMemoryStateStore INSTANCE = new NoOpMemoryStateStore();

        private NoOpMemoryStateStore() {
        }

        @Override
        public <T> T readState(String fileName, TypeReference<T> typeReference, Supplier<T> fallbackSupplier) {
            return fallbackSupplier.get();
        }

        @Override
        public void writeState(String fileName, Object value) {
        }
    }
}
