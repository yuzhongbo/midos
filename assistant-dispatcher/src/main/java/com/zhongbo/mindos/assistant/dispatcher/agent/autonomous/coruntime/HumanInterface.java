package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

public interface HumanInterface {

    HumanIntent capture();

    HumanFeedback getFeedback();

    Approval requestApproval(Action action);
}
