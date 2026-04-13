package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import org.springframework.stereotype.Component;

@Component
public class HumanRuntimeSessionManager {

    private final ThreadLocal<HumanRuntimeSession> currentSession = new ThreadLocal<>();

    public HumanRuntimeSession activate(HumanRuntimeSession session) {
        HumanRuntimeSession safeSession = session == null ? HumanRuntimeSession.empty() : session;
        currentSession.set(safeSession);
        return safeSession;
    }

    public HumanRuntimeSession current() {
        HumanRuntimeSession session = currentSession.get();
        return session == null ? HumanRuntimeSession.empty() : session;
    }

    public void clear() {
        currentSession.remove();
    }
}
