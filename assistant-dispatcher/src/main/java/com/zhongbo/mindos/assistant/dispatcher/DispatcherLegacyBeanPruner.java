package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DefaultExecutionMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.PostExecutionMemoryRecorder;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.SimpleParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
final class DispatcherLegacyBeanPruner implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    static final String LEGACY_BEANS_PROPERTY = "mindos.dispatcher.legacy-beans.enabled";
    static final String AUTONOMOUS_RUNTIME_PROPERTY = "mindos.autonomous.runtime.enabled";

    private static final String AGENT_PREFIX = "com.zhongbo.mindos.assistant.dispatcher.agent.";
    private static final String AUTONOMOUS_AGENT_PREFIX = AGENT_PREFIX + "autonomous.";
    private static final String PROCEDURE_AGENT_PREFIX = AGENT_PREFIX + "procedure.";
    private static final List<String> LEGACY_AGENT_PREFIXES = List.of(
            AGENT_PREFIX + "multiagent.",
            AGENT_PREFIX + "network.",
            AGENT_PREFIX + "runtime.",
            AGENT_PREFIX + "search."
    );

    private static final String ORCHESTRATOR_PREFIX = "com.zhongbo.mindos.assistant.dispatcher.orchestrator.";
    private static final String ORCHESTRATOR_STEP5_PREFIX = ORCHESTRATOR_PREFIX + "step5.";
    private static final Set<String> ALWAYS_ACTIVE_ORCHESTRATOR_BEANS = Set.of(
            InMemoryParamSchemaRegistry.class.getName(),
            SimpleParamValidator.class.getName()
    );
    private static final Set<String> AUTONOMOUS_ONLY_ORCHESTRATOR_BEANS = Set.of(
            DefaultExecutionMemoryFacade.class.getName(),
            PostExecutionMemoryRecorder.class.getName(),
            OrchestratorMemoryWriter.class.getName()
    );

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        boolean legacyBeansEnabled = propertyEnabled(LEGACY_BEANS_PROPERTY);
        boolean autonomousRuntimeEnabled = propertyEnabled(AUTONOMOUS_RUNTIME_PROPERTY);
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition beanDefinition = registry.getBeanDefinition(beanName);
            if (shouldPrune(beanClassName(beanDefinition), legacyBeansEnabled, autonomousRuntimeEnabled)) {
                registry.removeBeanDefinition(beanName);
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
    }

    static boolean shouldPrune(String className,
                               boolean legacyBeansEnabled,
                               boolean autonomousRuntimeEnabled) {
        if (className == null || className.isBlank()) {
            return false;
        }
        if (ALWAYS_ACTIVE_ORCHESTRATOR_BEANS.contains(className)) {
            return false;
        }
        if (className.startsWith(AUTONOMOUS_AGENT_PREFIX)) {
            return !autonomousRuntimeEnabled;
        }
        if (className.startsWith(PROCEDURE_AGENT_PREFIX) || className.startsWith(ORCHESTRATOR_STEP5_PREFIX)) {
            return !(autonomousRuntimeEnabled || legacyBeansEnabled);
        }
        if (AUTONOMOUS_ONLY_ORCHESTRATOR_BEANS.contains(className)) {
            return !autonomousRuntimeEnabled;
        }
        if (startsWithAny(className, LEGACY_AGENT_PREFIXES)) {
            return !legacyBeansEnabled;
        }
        if (className.startsWith(ORCHESTRATOR_PREFIX)) {
            return !legacyBeansEnabled;
        }
        return false;
    }

    private boolean propertyEnabled(String key) {
        return environment != null && environment.getProperty(key, Boolean.class, false);
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        if (value == null || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isBlank() && value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String beanClassName(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return "";
        }
        String className = beanDefinition.getBeanClassName();
        if (className != null && !className.isBlank()) {
            return className;
        }
        if (beanDefinition instanceof AbstractBeanDefinition abstractBeanDefinition && abstractBeanDefinition.hasBeanClass()) {
            return abstractBeanDefinition.getBeanClass().getName();
        }
        return "";
    }
}
