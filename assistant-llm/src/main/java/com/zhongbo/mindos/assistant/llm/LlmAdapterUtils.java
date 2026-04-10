package com.zhongbo.mindos.assistant.llm;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small utility to centralize LLM adapter error handling.
 * This is a non-breaking preparatory refactor for later decoupling.
 */
public final class LlmAdapterUtils {
    private static final Logger LOGGER = Logger.getLogger(LlmAdapterUtils.class.getName());

    private LlmAdapterUtils() {}

    public static <T> T safeCall(Supplier<T> action, T fallback, String operation) {
        try {
            return action.get();
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "LLM adapter operation failed: " + operation, ex);
            return fallback;
        }
    }
}
