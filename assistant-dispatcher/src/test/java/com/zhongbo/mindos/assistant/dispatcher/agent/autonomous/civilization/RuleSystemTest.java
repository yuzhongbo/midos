package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSystemTest {

    @Test
    void shouldRejectHighCostTransactionWithoutApproval() {
        RuleSystem ruleSystem = new RuleSystem();

        Transaction transaction = new Transaction(
                "tx-1",
                "civilization-commons",
                "atlas-org",
                Map.of(ResourceType.COMPUTE, 40.0),
                65.0,
                0.4,
                false,
                true,
                TransactionStatus.PENDING,
                "high cost",
                Instant.now()
        );

        assertFalse(ruleSystem.validate(transaction));
        assertTrue(ruleSystem.validate(new Transaction(
                "tx-2",
                "civilization-commons",
                "atlas-org",
                Map.of(ResourceType.COMPUTE, 40.0),
                65.0,
                0.4,
                true,
                true,
                TransactionStatus.PENDING,
                "approved",
                Instant.now()
        )));
    }
}
