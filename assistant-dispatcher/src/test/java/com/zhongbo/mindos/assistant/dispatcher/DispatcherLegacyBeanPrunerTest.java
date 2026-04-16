package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousLoopEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime.HumanAICoRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.DefaultPlannerAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DefaultDecisionPlanner;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.DefaultPolicyUpdater;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherLegacyBeanPrunerTest {

    @Test
    void shouldPruneLegacyAndAutonomousBeansByDefault() {
        DefaultListableBeanFactory registry = registry();

        pruner(Map.of()).postProcessBeanDefinitionRegistry(registry);

        assertFalse(registry.containsBeanDefinition("plannerAgent"));
        assertFalse(registry.containsBeanDefinition("decisionPlanner"));
        assertFalse(registry.containsBeanDefinition("humanCoRuntime"));
        assertFalse(registry.containsBeanDefinition("autonomousLoop"));
        assertFalse(registry.containsBeanDefinition("proceduralMemory"));
        assertFalse(registry.containsBeanDefinition("policyUpdater"));
        assertTrue(registry.containsBeanDefinition("paramSchemaRegistry"));
        assertTrue(registry.containsBeanDefinition("paramValidator"));
    }

    @Test
    void shouldKeepLegacyPlannerBeansWithoutRevivingAutonomousRuntimeWhenLegacyModeEnabled() {
        DefaultListableBeanFactory registry = registry();

        pruner(Map.of(DispatcherLegacyBeanPruner.LEGACY_BEANS_PROPERTY, "true"))
                .postProcessBeanDefinitionRegistry(registry);

        assertTrue(registry.containsBeanDefinition("plannerAgent"));
        assertTrue(registry.containsBeanDefinition("decisionPlanner"));
        assertTrue(registry.containsBeanDefinition("proceduralMemory"));
        assertTrue(registry.containsBeanDefinition("policyUpdater"));
        assertFalse(registry.containsBeanDefinition("humanCoRuntime"));
        assertFalse(registry.containsBeanDefinition("autonomousLoop"));
    }

    @Test
    void shouldKeepAutonomousBeansWithoutReopeningLegacyPlannerGraphWhenAutonomousRuntimeEnabled() {
        DefaultListableBeanFactory registry = registry();

        pruner(Map.of(DispatcherLegacyBeanPruner.AUTONOMOUS_RUNTIME_PROPERTY, "true"))
                .postProcessBeanDefinitionRegistry(registry);

        assertTrue(registry.containsBeanDefinition("humanCoRuntime"));
        assertTrue(registry.containsBeanDefinition("autonomousLoop"));
        assertTrue(registry.containsBeanDefinition("proceduralMemory"));
        assertTrue(registry.containsBeanDefinition("policyUpdater"));
        assertFalse(registry.containsBeanDefinition("plannerAgent"));
        assertFalse(registry.containsBeanDefinition("decisionPlanner"));
        assertTrue(registry.containsBeanDefinition("paramSchemaRegistry"));
        assertTrue(registry.containsBeanDefinition("paramValidator"));
    }

    private DispatcherLegacyBeanPruner pruner(Map<String, Object> properties) {
        DispatcherLegacyBeanPruner pruner = new DispatcherLegacyBeanPruner();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        pruner.setEnvironment(environment);
        return pruner;
    }

    private DefaultListableBeanFactory registry() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        registry.registerBeanDefinition("plannerAgent", new RootBeanDefinition(DefaultPlannerAgent.class));
        registry.registerBeanDefinition("decisionPlanner", new RootBeanDefinition(DefaultDecisionPlanner.class));
        registry.registerBeanDefinition("humanCoRuntime", new RootBeanDefinition(HumanAICoRuntime.class));
        registry.registerBeanDefinition("autonomousLoop", new RootBeanDefinition(AutonomousLoopEngine.class));
        registry.registerBeanDefinition("proceduralMemory", new RootBeanDefinition(ProceduralMemory.class));
        registry.registerBeanDefinition("policyUpdater", new RootBeanDefinition(DefaultPolicyUpdater.class));
        registry.registerBeanDefinition("paramSchemaRegistry", new RootBeanDefinition(InMemoryParamSchemaRegistry.class));
        registry.registerBeanDefinition("paramValidator", new RootBeanDefinition(SimpleParamValidator.class));
        return registry;
    }
}
