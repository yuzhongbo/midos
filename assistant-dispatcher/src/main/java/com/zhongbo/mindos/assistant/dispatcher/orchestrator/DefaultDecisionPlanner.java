package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.DecisionSignal;
import com.zhongbo.mindos.assistant.dispatcher.FinalPlanner;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
@Component
public class DefaultDecisionPlanner implements DecisionPlanner {

    private final FinalPlanner finalPlanner;

    public DefaultDecisionPlanner() {
        this((SkillCatalogFacade) null, null);
    }

    public DefaultDecisionPlanner(SkillCatalogFacade skillEngine) {
        this(skillEngine, null);
    }

    @Autowired
    public DefaultDecisionPlanner(SkillCatalogFacade skillEngine,
                                  DispatcherMemoryFacade dispatcherMemoryFacade) {
        this.finalPlanner = new FinalPlanner(skillEngine, dispatcherMemoryFacade);
    }

    DefaultDecisionPlanner(FinalPlanner finalPlanner) {
        this.finalPlanner = finalPlanner == null ? new FinalPlanner() : finalPlanner;
    }

    @Override
    public Decision plan(DecisionOrchestrator.UserInput input) {
        return finalPlanner.plan(input);
    }

    @Override
    public Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals) {
        return finalPlanner.plan(input, signals);
    }

    @Override
    public Decision replan(DecisionOrchestrator.UserInput input, Decision failedDecision) {
        return finalPlanner.replan(input, failedDecision);
    }
}
