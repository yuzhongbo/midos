package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record Approval(ApprovalStatus status,
                       String approverId,
                       String reason,
                       Map<String, Object> overrideAttributes,
                       Instant decidedAt) {

    public Approval {
        status = status == null ? ApprovalStatus.PENDING : status;
        approverId = approverId == null ? "" : approverId.trim();
        reason = reason == null ? "" : reason.trim();
        overrideAttributes = overrideAttributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(overrideAttributes));
        decidedAt = decidedAt == null ? Instant.now() : decidedAt;
    }

    public static Approval approved(String reason) {
        return new Approval(ApprovalStatus.APPROVED, "", reason, Map.of(), Instant.now());
    }

    public static Approval pending(String reason) {
        return new Approval(ApprovalStatus.PENDING, "", reason, Map.of(), Instant.now());
    }

    public static Approval rejected(String reason) {
        return new Approval(ApprovalStatus.REJECTED, "", reason, Map.of(), Instant.now());
    }

    public static Approval modified(String reason, Map<String, Object> overrides) {
        return new Approval(ApprovalStatus.MODIFIED, "", reason, overrides, Instant.now());
    }

    public boolean allowExecution() {
        return status == ApprovalStatus.APPROVED;
    }

    public boolean waiting() {
        return status == ApprovalStatus.PENDING;
    }

    public boolean rejected() {
        return status == ApprovalStatus.REJECTED;
    }

    public boolean modified() {
        return status == ApprovalStatus.MODIFIED;
    }
}
