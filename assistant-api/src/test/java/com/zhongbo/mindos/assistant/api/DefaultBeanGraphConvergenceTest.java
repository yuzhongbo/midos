package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousLoopEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanAICoRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.SharedDecisionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.DefaultPlannerAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.DefaultToolAgent;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DefaultDecisionPlanner;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DefaultTaskGraphPlanner;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = "mindos.memory.file-repo.enabled=false")
class DefaultBeanGraphConvergenceTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldKeepHermesSupportBeansWhileHidingLegacySecondaryBrainsByDefault() {
        assertNotNull(applicationContext.getBeanProvider(ParamValidator.class).getIfAvailable());
        assertNotNull(applicationContext.getBeanProvider(ParamSchemaRegistry.class).getIfAvailable());
        assertNotNull(applicationContext.getBeanProvider(InMemoryParamSchemaRegistry.class).getIfAvailable());

        assertNull(applicationContext.getBeanProvider(DefaultPlannerAgent.class).getIfAvailable());
        assertNull(applicationContext.getBeanProvider(DefaultToolAgent.class).getIfAvailable());
        assertNull(applicationContext.getBeanProvider(DefaultDecisionPlanner.class).getIfAvailable());
        assertNull(applicationContext.getBeanProvider(DefaultTaskGraphPlanner.class).getIfAvailable());
        assertNull(applicationContext.getBeanProvider(SharedDecisionEngine.class).getIfAvailable());
        assertNull(applicationContext.getBeanProvider(HumanAICoRuntime.class).getIfAvailable());
        assertNull(applicationContext.getBeanProvider(AutonomousLoopEngine.class).getIfAvailable());
    }
}
