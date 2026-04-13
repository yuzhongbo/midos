package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class CivilizationScheduler {

    private final AIOrganizationMarket market;
    private final EconomicSystem economicSystem;
    private final RuleSystem ruleSystem;
    private final ReputationSystem reputationSystem;

    public CivilizationScheduler(AIOrganizationMarket market,
                                 EconomicSystem economicSystem,
                                 RuleSystem ruleSystem,
                                 ReputationSystem reputationSystem) {
        this.market = market;
        this.economicSystem = economicSystem;
        this.ruleSystem = ruleSystem;
        this.reputationSystem = reputationSystem;
    }

    public CivilizationAssignment distribute(Goal goal,
                                             DigitalCivilization civilization,
                                             AutonomousPlanningContext context) {
        if (civilization == null) {
            return CivilizationAssignment.empty(null, List.of(), null);
        }
        TaskRequest request = buildRequest(goal, context);
        List<Offer> offers = market == null
                ? List.of()
                : market.broadcast(request, civilization.organizations(), civilization.resources(), reputationSystem);
        if (offers.isEmpty()) {
            return CivilizationAssignment.empty(request, List.of(), null);
        }
        List<Offer> rankedOffers = new ArrayList<>(offers);
        rankedOffers.sort(java.util.Comparator.comparingDouble(Offer::marketScore).reversed());
        Transaction lastTransaction = null;
        for (Offer offer : rankedOffers) {
            boolean approvalGranted = !request.requiresApproval()
                    || rankedOffers.size() >= 2
                    || offer.reputationScore() >= 0.75;
            TransactionRequest transactionRequest = new TransactionRequest(
                    civilization.resources() == null ? "civilization-commons" : civilization.resources().commonsOwner(),
                    offer.orgId(),
                    offer.resourceCommitment(),
                    offer.quotedCost(),
                    request.priority(),
                    "goal:" + (goal == null ? "" : goal.goalId()),
                    approvalGranted,
                    true,
                    Map.of(
                            "goal", goal == null ? "" : goal.description(),
                            "marketScore", offer.marketScore()
                    )
            );
            lastTransaction = economicSystem == null
                    ? new Transaction("tx:none", transactionRequest.requesterOrgId(), transactionRequest.providerOrgId(), transactionRequest.resourceAmounts(), transactionRequest.totalCost(), transactionRequest.priorityStake(), transactionRequest.approvalGranted(), true, TransactionStatus.SETTLED, "economic system unavailable", java.time.Instant.now())
                    : economicSystem.executeMarket(transactionRequest);
            if (lastTransaction.settled() && (ruleSystem == null || ruleSystem.validate(lastTransaction))) {
                return new CivilizationAssignment(request, offer, rankedOffers, lastTransaction);
            }
        }
        return CivilizationAssignment.empty(request, rankedOffers, lastTransaction);
    }

    private TaskRequest buildRequest(Goal goal, AutonomousPlanningContext context) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        String requesterOrgId = context == null || context.profileContext() == null
                ? "civilization-commons"
                : String.valueOf(context.profileContext().getOrDefault("requesterOrgId", "civilization-commons"));
        List<String> focusAreas = extractFocusAreas(safeGoal.description());
        double complexity = 1.0 + focusAreas.size() * 0.3 + Math.min(2.0, safeGoal.description().length() / 80.0);
        Map<ResourceType, Double> requiredResources = new LinkedHashMap<>();
        requiredResources.put(ResourceType.COMPUTE, 10.0 * complexity);
        requiredResources.put(ResourceType.MEMORY, 8.0 * complexity);
        requiredResources.put(ResourceType.TOOL_USAGE, 6.0 * complexity);
        requiredResources.put(ResourceType.AGENT_TIME, 7.0 * complexity);
        requiredResources.put(ResourceType.TASK_PRIORITY, 4.0 + safeGoal.priority() * 10.0);
        double maxCost = requiredResources.values().stream().mapToDouble(Double::doubleValue).sum() * 1.4;
        boolean requiresApproval = maxCost > (ruleSystem == null ? 40.0 : ruleSystem.threshold(RuleType.HIGH_COST_APPROVAL_REQUIRED, 40.0))
                || safeGoal.priority() >= 0.85;
        return new TaskRequest(
                requesterOrgId,
                safeGoal,
                focusAreas,
                requiredResources,
                maxCost,
                safeGoal.priority(),
                requiresApproval,
                Map.of("civilizationRequest", true)
        );
    }

    private List<String> extractFocusAreas(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> focusAreas = new LinkedHashSet<>();
        for (String part : description.toLowerCase(java.util.Locale.ROOT).split("[\\s,，。;；]+")) {
            if (!part.isBlank()) {
                focusAreas.add(part.trim());
            }
            if (focusAreas.size() >= 5) {
                break;
            }
        }
        return List.copyOf(focusAreas);
    }
}
