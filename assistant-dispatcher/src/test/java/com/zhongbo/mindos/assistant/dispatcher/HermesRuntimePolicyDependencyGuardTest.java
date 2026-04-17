package com.zhongbo.mindos.assistant.dispatcher;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesRuntimePolicyDependencyGuardTest {

    @Test
    void activeHermesRuntimeClassesShouldDependOnUnifiedPolicyCore() {
        assertDependsOnUnifiedPolicy(HermesDecisionEngine.class);
        assertDependsOnUnifiedPolicy(HermesMemoryRecorder.class);
        assertDependsOnUnifiedPolicy(HermesExecutionGuard.class);
        assertDependsOnUnifiedPolicy(DispatchMemoryLifecycle.class);
    }

    private void assertDependsOnUnifiedPolicy(Class<?> type) {
        Set<String> fieldTypeNames = Arrays.stream(type.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertTrue(fieldTypeNames.contains(HermesDecisionPolicy.class.getName()),
                () -> type.getSimpleName() + " should depend on HermesDecisionPolicy");
        assertFalse(fieldTypeNames.contains(BehaviorRoutingSupport.class.getName()),
                () -> type.getSimpleName() + " should not directly depend on BehaviorRoutingSupport");
        assertFalse(fieldTypeNames.contains(SemanticRoutingSupport.class.getName()),
                () -> type.getSimpleName() + " should not directly depend on SemanticRoutingSupport");
    }
}
