package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

public class CivilizationState {

    private volatile DigitalCivilization civilization;
    private final CivilizationMemory memory;

    public CivilizationState(DigitalCivilization civilization,
                             CivilizationMemory memory) {
        this.civilization = civilization;
        this.memory = memory;
    }

    public DigitalCivilization civilization() {
        return civilization;
    }

    public void civilization(DigitalCivilization civilization) {
        this.civilization = civilization;
    }

    public CivilizationMemory memory() {
        return memory;
    }
}
