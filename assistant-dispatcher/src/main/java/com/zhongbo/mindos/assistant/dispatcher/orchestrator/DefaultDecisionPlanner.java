package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.DecisionSignal;
import com.zhongbo.mindos.assistant.dispatcher.FinalPlanner;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DefaultDecisionPlanner implements DecisionPlanner {

    private final FinalPlanner finalPlanner;

    public DefaultDecisionPlanner() {
        this((SkillCatalogFacade) null);
    }

    @Autowired
    public DefaultDecisionPlanner(SkillCatalogFacade skillEngine) {
        this.finalPlanner = new FinalPlanner();
    }

    DefaultDecisionPlanner(FinalPlanner finalPlanner) {
        this.finalPlanner = finalPlanner == null ? new FinalPlanner() : finalPlanner;
    }

    @Override
    public Decision plan(DecisionOrchestrator.UserInput input, List<DecisionSignal> signals) {
        return finalPlanner.plan(input, signals);
    }
}
