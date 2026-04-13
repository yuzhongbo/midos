package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RuntimeHumanInterface implements HumanInterface {

    private final HumanRuntimeSessionManager sessionManager;

    public RuntimeHumanInterface(HumanRuntimeSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public HumanIntent capture() {
        return sessionManager == null ? HumanIntent.empty() : sessionManager.current().intent();
    }

    @Override
    public HumanFeedback getFeedback() {
        if (sessionManager == null) {
            return HumanFeedback.empty();
        }
        HumanFeedback feedback = sessionManager.current().nextFeedback();
        return feedback == null ? HumanFeedback.empty() : feedback;
    }

    @Override
    public Approval requestApproval(Action action) {
        if (sessionManager == null) {
            return Approval.pending("awaiting-human-approval");
        }
        HumanRuntimeSession session = sessionManager.current();
        Approval approval = session.nextApproval();
        if (approval != null) {
            return approval;
        }
        Object defaultMode = session.attributes().get("human.approval.default");
        if (defaultMode != null) {
            String normalized = String.valueOf(defaultMode).trim().toLowerCase(Locale.ROOT);
            if ("approve".equals(normalized) || "approved".equals(normalized)) {
                return Approval.approved("session-default-approval");
            }
            if ("reject".equals(normalized) || "rejected".equals(normalized) || "deny".equals(normalized)) {
                return Approval.rejected("session-default-rejection");
            }
        }
        return Approval.pending(action == null || action.summary().isBlank() ? "awaiting-human-approval" : "awaiting-human-approval:" + action.summary());
    }
}
