package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganizationRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.EvaluationDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.ExecutionDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgDecisionEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgRestructuringEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationCycleResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.PlanningDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.StrategyDepartmentService;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.PlannerAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DigitalCivilizationRuntime {

    private final StrategyDepartmentService strategyDepartmentService;
    private final PlanningDepartmentService planningDepartmentService;
    private final ExecutionDepartmentService executionDepartmentService;
    private final EvaluationDepartmentService evaluationDepartmentService;
    private final OrgDecisionEngine orgDecisionEngine;
    private final OrgRestructuringEngine orgRestructuringEngine;
    private final CivilizationScheduler scheduler;
    private final CivilizationEvolutionEngine evolutionEngine;
    private final CivilizationMemory civilizationMemory;
    private final ReputationSystem reputationSystem;
    private final CivilizationFactory civilizationFactory;
    private final CivilizationState state;
    private final Map<String, OrganizationNode> organizationNodes = new ConcurrentHashMap<>();

    @Autowired
    public DigitalCivilizationRuntime(StrategyDepartmentService strategyDepartmentService,
                                      PlanningDepartmentService planningDepartmentService,
                                      ExecutionDepartmentService executionDepartmentService,
                                      EvaluationDepartmentService evaluationDepartmentService,
                                      OrgDecisionEngine orgDecisionEngine,
                                      OrgRestructuringEngine orgRestructuringEngine,
                                      CivilizationScheduler scheduler,
                                      CivilizationEvolutionEngine evolutionEngine,
                                      CivilizationMemory civilizationMemory,
                                      ReputationSystem reputationSystem,
                                      CivilizationFactory civilizationFactory,
                                      EconomicSystem economicSystem,
                                      RuleSystem ruleSystem,
                                      ResourceSystem resourceSystem,
                                      List<PlannerAgent> plannerAgents) {
        this.strategyDepartmentService = strategyDepartmentService;
        this.planningDepartmentService = planningDepartmentService;
        this.executionDepartmentService = executionDepartmentService;
        this.evaluationDepartmentService = evaluationDepartmentService;
        this.orgDecisionEngine = orgDecisionEngine;
        this.orgRestructuringEngine = orgRestructuringEngine;
        this.scheduler = scheduler;
        this.evolutionEngine = evolutionEngine;
        this.civilizationMemory = civilizationMemory;
        this.reputationSystem = reputationSystem;
        this.civilizationFactory = civilizationFactory;
        this.state = new CivilizationState(
                civilizationFactory.bootstrap(
                        economicSystem,
                        ruleSystem,
                        resourceSystem,
                        plannerAgents == null ? List.of() : plannerAgents.stream().map(PlannerAgent::agentId).toList()
                ),
                civilizationMemory
        );
        syncOrganizationNodes();
    }

    protected DigitalCivilizationRuntime(DigitalCivilization civilization,
                                         CivilizationMemory civilizationMemory) {
        this.strategyDepartmentService = null;
        this.planningDepartmentService = null;
        this.executionDepartmentService = null;
        this.evaluationDepartmentService = null;
        this.orgDecisionEngine = null;
        this.orgRestructuringEngine = null;
        this.scheduler = null;
        this.evolutionEngine = null;
        this.civilizationMemory = civilizationMemory == null ? new CivilizationMemory() : civilizationMemory;
        this.reputationSystem = null;
        this.civilizationFactory = null;
        this.state = new CivilizationState(civilization, this.civilizationMemory);
    }

    public CivilizationCycleResult runCycle(Goal goal, AutonomousPlanningContext context) {
        DigitalCivilization before = state.civilization();
        CivilizationAssignment assignment = scheduler == null
                ? CivilizationAssignment.empty(null, List.of(), null)
                : scheduler.distribute(goal, before, context);
        if (assignment == null || !assignment.assigned()) {
            CivilizationMemory.CivilizationTrace trace = civilizationMemory == null
                    ? null
                    : civilizationMemory.record(before, before, assignment, null, reputationSnapshot(before), resourceSnapshot(before));
            return new CivilizationCycleResult(before, before, assignment, null, trace);
        }
        OrganizationNode node = organizationNodes.get(assignment.selectedOrgId());
        if (node == null) {
            CivilizationMemory.CivilizationTrace trace = civilizationMemory == null
                    ? null
                    : civilizationMemory.record(before, before, assignment, null, reputationSnapshot(before), resourceSnapshot(before));
            return new CivilizationCycleResult(before, before, assignment, null, trace);
        }
        AutonomousPlanningContext enrichedContext = enrichContext(context, node.unit, assignment);
        OrganizationCycleResult organizationCycle = node.runtime.runCycle(goal, enrichedContext);
        double reputation = reputationSystem == null
                ? node.unit.reputation()
                : reputationSystem.update(node.unit, node.orgMemory, organizationCycle);
        CivilizationUnit updatedUnit = node.unit
                .withOrganization(organizationCycle.organizationAfter())
                .withBudget(before.economy() == null ? node.unit.budget() : before.economy().budgetOf(node.unit.orgId()))
                .withResourcePortfolio(before.resources() == null ? node.unit.resourcePortfolio() : before.resources().balancesOf(node.unit.orgId()))
                .withReputation(reputation);
        organizationNodes.put(updatedUnit.orgId(), node.withUnit(updatedUnit));
        state.civilization(replaceUnit(before, updatedUnit).withMetadata(Map.of(
                "lastSelectedOrgId", updatedUnit.orgId(),
                "lastTransactionStatus", assignment.transaction() == null ? "none" : assignment.transaction().status().name()
        )));
        evolutionEngine.evolve(state);
        syncOrganizationNodes();
        DigitalCivilization after = state.civilization();
        CivilizationMemory.CivilizationTrace trace = civilizationMemory == null
                ? null
                : civilizationMemory.record(before, after, assignment, organizationCycle, reputationSnapshot(after), resourceSnapshot(after));
        return new CivilizationCycleResult(before, after, assignment, organizationCycle, trace);
    }

    public DigitalCivilization currentCivilization() {
        return state.civilization();
    }

    private AutonomousPlanningContext enrichContext(AutonomousPlanningContext context,
                                                    CivilizationUnit unit,
                                                    CivilizationAssignment assignment) {
        AutonomousPlanningContext safeContext = AutonomousPlanningContext.safe(context);
        LinkedHashMap<String, Object> profile = new LinkedHashMap<>(safeContext.profileContext());
        profile.put("requesterOrgId", assignment.request() == null ? "civilization-commons" : assignment.request().requesterOrgId());
        profile.put("civilizationName", state.civilization() == null ? "Digital Civilization" : state.civilization().civilizationName());
        profile.put("selectedOrgId", unit.orgId());
        profile.put("selectedOrgReputation", unit.reputation());
        profile.put("selectedOrgBudget", unit.budget().availableCredits());
        profile.put("selectedOrgResources", unit.resourcePortfolio());
        profile.put("marketOfferScore", assignment.selectedOffer() == null ? 0.0 : assignment.selectedOffer().marketScore());
        profile.put("marketOfferCost", assignment.selectedOffer() == null ? 0.0 : assignment.selectedOffer().quotedCost());
        return new AutonomousPlanningContext(
                safeContext.userId(),
                safeContext.userInput(),
                profile,
                safeContext.goalMemory(),
                safeContext.iteration(),
                safeContext.lastResult(),
                safeContext.lastEvaluation(),
                safeContext.excludedTargets()
        );
    }

    private void syncOrganizationNodes() {
        DigitalCivilization civilization = state.civilization();
        if (civilization == null) {
            return;
        }
        Map<String, CivilizationUnit> currentUnits = new LinkedHashMap<>();
        for (CivilizationUnit unit : civilization.organizations()) {
            if (unit == null) {
                continue;
            }
            currentUnits.put(unit.orgId(), unit);
            organizationNodes.computeIfAbsent(unit.orgId(), ignored -> {
                OrgMemory orgMemory = new OrgMemory();
                AIOrganizationRuntime runtime = new AIOrganizationRuntime(
                        strategyDepartmentService,
                        planningDepartmentService,
                        executionDepartmentService,
                        evaluationDepartmentService,
                        orgDecisionEngine,
                        orgRestructuringEngine,
                        orgMemory,
                        unit.organization()
                );
                return new OrganizationNode(unit, runtime, orgMemory);
            });
        }
        organizationNodes.entrySet().removeIf(entry -> !currentUnits.containsKey(entry.getKey()));
        for (Map.Entry<String, CivilizationUnit> entry : currentUnits.entrySet()) {
            OrganizationNode node = organizationNodes.get(entry.getKey());
            if (node != null) {
                organizationNodes.put(entry.getKey(), node.withUnit(entry.getValue()));
            }
        }
    }

    private DigitalCivilization replaceUnit(DigitalCivilization civilization, CivilizationUnit updatedUnit) {
        if (civilization == null || updatedUnit == null) {
            return civilization;
        }
        List<CivilizationUnit> updatedUnits = civilization.organizations().stream()
                .map(unit -> unit != null && unit.orgId().equalsIgnoreCase(updatedUnit.orgId()) ? updatedUnit : unit)
                .toList();
        return civilization.withOrganizations(updatedUnits);
    }

    private Map<String, Double> reputationSnapshot(DigitalCivilization civilization) {
        if (civilization == null) {
            return Map.of();
        }
        LinkedHashMap<String, Double> snapshot = new LinkedHashMap<>();
        for (CivilizationUnit unit : civilization.organizations()) {
            if (unit != null) {
                snapshot.put(unit.orgId(), unit.reputation());
            }
        }
        return Map.copyOf(snapshot);
    }

    private Map<String, Map<ResourceType, Double>> resourceSnapshot(DigitalCivilization civilization) {
        if (civilization == null || civilization.resources() == null) {
            return Map.of();
        }
        LinkedHashMap<String, Map<ResourceType, Double>> snapshot = new LinkedHashMap<>();
        for (CivilizationUnit unit : civilization.organizations()) {
            if (unit != null) {
                snapshot.put(unit.orgId(), civilization.resources().balancesOf(unit.orgId()));
            }
        }
        return Map.copyOf(snapshot);
    }

    private static final class OrganizationNode {
        private final CivilizationUnit unit;
        private final AIOrganizationRuntime runtime;
        private final OrgMemory orgMemory;

        private OrganizationNode(CivilizationUnit unit,
                                 AIOrganizationRuntime runtime,
                                 OrgMemory orgMemory) {
            this.unit = unit;
            this.runtime = runtime;
            this.orgMemory = orgMemory;
        }

        private OrganizationNode withUnit(CivilizationUnit nextUnit) {
            return new OrganizationNode(nextUnit, runtime, orgMemory);
        }
    }
}
