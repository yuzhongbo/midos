package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class EconomicSystem {

    private final ResourceSystem resourceSystem;
    private final RuleSystem ruleSystem;
    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Transaction> ledger = new CopyOnWriteArrayList<>();

    public EconomicSystem(ResourceSystem resourceSystem,
                          RuleSystem ruleSystem) {
        this.resourceSystem = resourceSystem;
        this.ruleSystem = ruleSystem;
        registerOrganization(resourceSystem == null ? "civilization-commons" : resourceSystem.commonsOwner(), new Budget(1000.0, 0.0));
    }

    public void registerOrganization(String orgId, Budget budget) {
        if (orgId == null || orgId.isBlank()) {
            return;
        }
        budgets.put(orgId.trim(), budget == null ? new Budget(0.0, 0.0) : budget);
    }

    public Budget budgetOf(String orgId) {
        return budgets.getOrDefault(orgId == null ? "" : orgId.trim(), new Budget(0.0, 0.0));
    }

    public Transaction transfer(Resource from, Resource to, double amount) {
        Transaction transaction = new Transaction(
                "tx:" + UUID.randomUUID(),
                from == null ? "" : from.ownerId(),
                to == null ? "" : to.ownerId(),
                from == null || from.type() == null ? Map.of() : Map.of(from.type(), amount),
                amount,
                0.0,
                true,
                true,
                TransactionStatus.SETTLED,
                "resource transfer",
                Instant.now()
        );
        if (resourceSystem != null && from != null && to != null) {
            resourceSystem.transfer(from, to, amount);
        }
        ledger.add(transaction);
        return transaction;
    }

    public Transaction executeMarket(TransactionRequest request) {
        if (request == null) {
            return rejected("market request missing", null);
        }
        Budget requesterBudget = budgetOf(request.requesterOrgId());
        boolean requesterCanPay = requesterBudget.availableCredits() + 1e-9 >= request.totalCost();
        boolean providerHasResources = resourceSystem == null || resourceSystem.hasResources(request.providerOrgId(), request.resourceAmounts());
        Transaction candidate = new Transaction(
                "tx:" + UUID.randomUUID(),
                request.requesterOrgId(),
                request.providerOrgId(),
                request.resourceAmounts(),
                request.totalCost(),
                request.priorityStake(),
                request.approvalGranted(),
                request.marketMediated(),
                TransactionStatus.PENDING,
                request.purpose(),
                Instant.now()
        );
        if (!requesterCanPay) {
            return rejected("insufficient budget", candidate);
        }
        if (!providerHasResources) {
            return rejected("provider resource shortage", candidate);
        }
        if (ruleSystem != null && !ruleSystem.validate(candidate)) {
            return rejected("rule validation failed", candidate);
        }
        budgets.put(request.requesterOrgId(), requesterBudget.withCredits(requesterBudget.credits() - request.totalCost()));
        Budget providerBudget = budgetOf(request.providerOrgId());
        budgets.put(request.providerOrgId(), providerBudget.withCredits(providerBudget.credits() + request.totalCost()));
        if (resourceSystem != null) {
            resourceSystem.consume(request.providerOrgId(), request.resourceAmounts());
            if (request.priorityStake() > 0.0) {
                resourceSystem.transfer(
                        new Resource(request.requesterOrgId(), ResourceType.TASK_PRIORITY),
                        new Resource(request.providerOrgId(), ResourceType.TASK_PRIORITY),
                        request.priorityStake()
                );
            }
        }
        Transaction settled = new Transaction(
                candidate.transactionId(),
                candidate.requesterOrgId(),
                candidate.providerOrgId(),
                candidate.resourceAmounts(),
                candidate.totalCost(),
                candidate.priorityStake(),
                candidate.approvalGranted(),
                candidate.marketMediated(),
                TransactionStatus.SETTLED,
                candidate.summary(),
                candidate.executedAt()
        );
        ledger.add(settled);
        return settled;
    }

    public List<Transaction> ledger() {
        return List.copyOf(ledger);
    }

    public Map<String, Budget> budgets() {
        return Map.copyOf(budgets);
    }

    private Transaction rejected(String reason, Transaction candidate) {
        Transaction rejected = candidate == null
                ? new Transaction("tx:" + UUID.randomUUID(), "", "", Map.of(), 0.0, 0.0, false, false, TransactionStatus.REJECTED, reason, Instant.now())
                : new Transaction(candidate.transactionId(), candidate.requesterOrgId(), candidate.providerOrgId(), candidate.resourceAmounts(), candidate.totalCost(), candidate.priorityStake(), candidate.approvalGranted(), candidate.marketMediated(), TransactionStatus.REJECTED, reason, candidate.executedAt());
        ledger.add(rejected);
        return rejected;
    }
}
