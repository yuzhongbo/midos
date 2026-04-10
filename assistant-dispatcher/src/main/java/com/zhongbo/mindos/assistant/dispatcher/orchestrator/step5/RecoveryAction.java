package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RecoveryAction(ActionType type,
                             String nodeId,
                             String target,
                             String fallbackTarget,
                             String syntheticOutput,
                             int retryCount,
                             boolean optional,
                             List<String> clearKeys,
                             Map<String, Object> contextPatch,
                             String reason) {

    public enum ActionType {
        RETRY_NODE,
        FALLBACK_NODE,
        SKIP_NODE,
        ROLLBACK_STEP,
        PATCH_CONTEXT
    }

    public RecoveryAction {
        type = type == null ? ActionType.PATCH_CONTEXT : type;
        nodeId = normalize(nodeId);
        target = normalize(target);
        fallbackTarget = normalize(fallbackTarget);
        syntheticOutput = syntheticOutput == null ? "" : syntheticOutput.trim();
        retryCount = Math.max(0, retryCount);
        clearKeys = clearKeys == null ? List.of() : List.copyOf(clearKeys);
        contextPatch = contextPatch == null ? Map.of() : Map.copyOf(contextPatch);
        reason = reason == null ? "" : reason.trim();
    }

    public static RecoveryAction retry(String nodeId,
                                       String target,
                                       int retryCount,
                                       List<String> clearKeys,
                                       Map<String, Object> contextPatch,
                                       String reason) {
        return new RecoveryAction(ActionType.RETRY_NODE, nodeId, target, "", "", retryCount, false, clearKeys, contextPatch, reason);
    }

    public static RecoveryAction fallback(String nodeId,
                                          String target,
                                          String fallbackTarget,
                                          String syntheticOutput,
                                          List<String> clearKeys,
                                          Map<String, Object> contextPatch,
                                          String reason) {
        return new RecoveryAction(ActionType.FALLBACK_NODE, nodeId, target, fallbackTarget, syntheticOutput, 1, false, clearKeys, contextPatch, reason);
    }

    public static RecoveryAction skip(String nodeId,
                                      String target,
                                      String syntheticOutput,
                                      boolean optional,
                                      List<String> clearKeys,
                                      Map<String, Object> contextPatch,
                                      String reason) {
        return new RecoveryAction(ActionType.SKIP_NODE, nodeId, target, "", syntheticOutput, 0, optional, clearKeys, contextPatch, reason);
    }

    public static RecoveryAction rollback(String nodeId,
                                          String target,
                                          List<String> clearKeys,
                                          Map<String, Object> contextPatch,
                                          String reason) {
        return new RecoveryAction(ActionType.ROLLBACK_STEP, nodeId, target, "", "", 0, false, clearKeys, contextPatch, reason);
    }

    public static RecoveryAction patch(String reason, Map<String, Object> contextPatch) {
        return new RecoveryAction(ActionType.PATCH_CONTEXT, "", "", "", "", 0, false, List.of(), contextPatch, reason);
    }

    public boolean isNodeAction() {
        return type == ActionType.RETRY_NODE || type == ActionType.FALLBACK_NODE || type == ActionType.SKIP_NODE;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.isBlank() ? "" : normalized;
    }
}
