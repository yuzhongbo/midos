package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.runtime.DualProcessCoordinator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DefaultDecisionPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacySurfaceRegistrationGuardTest {

    @Test
    void defaultDecisionPlannerShouldRemainExplicitLegacyOptIn() {
        ConditionalOnProperty conditional = DefaultDecisionPlanner.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(conditional);
        assertArrayEquals(new String[]{"mindos.dispatcher.legacy-beans.enabled"}, conditional.name());
        assertEquals("true", conditional.havingValue());
        assertFalse(conditional.matchIfMissing());
    }

    @Test
    void autonomousPlannerShouldRemainExplicitAutonomousOptIn() {
        ConditionalOnProperty conditional = AutonomousPlanner.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(conditional);
        assertArrayEquals(new String[]{"mindos.autonomous.runtime.enabled"}, conditional.name());
        assertEquals("true", conditional.havingValue());
        assertFalse(conditional.matchIfMissing());
    }

    @Test
    void dualProcessCoordinatorShouldStayOutOfSpringBeanGraph() {
        assertNull(DualProcessCoordinator.class.getAnnotation(Component.class));
        assertNull(DualProcessCoordinator.class.getAnnotation(Service.class));
    }
}
