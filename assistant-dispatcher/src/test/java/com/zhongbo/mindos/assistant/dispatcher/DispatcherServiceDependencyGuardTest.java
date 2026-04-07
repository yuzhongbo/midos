package com.zhongbo.mindos.assistant.dispatcher;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DispatcherServiceDependencyGuardTest {

    @Test
    void dispatcherConstructorShouldNotDependOnApiLayerTypes() {
        Constructor<?> constructor = Arrays.stream(DispatcherService.class.getConstructors())
                .max((left, right) -> Integer.compare(left.getParameterCount(), right.getParameterCount()))
                .orElseThrow();

        boolean hasApiLayerDependency = Arrays.stream(constructor.getParameterTypes())
                .map(Class::getName)
                .anyMatch(name -> name.startsWith("com.zhongbo.mindos.assistant.api."));

        assertFalse(hasApiLayerDependency, "DispatcherService should not depend on assistant-api package types");
    }
}

