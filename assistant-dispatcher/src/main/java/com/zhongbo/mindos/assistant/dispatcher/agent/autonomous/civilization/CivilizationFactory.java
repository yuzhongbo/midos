package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.Department;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationAgent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CivilizationFactory {

    public DigitalCivilization bootstrap(EconomicSystem economy,
                                         RuleSystem rules,
                                         ResourceSystem resources,
                                         List<String> plannerAgentIds) {
        CivilizationUnit atlas = createUnit(
                "atlas-org",
                "Atlas Stability Works",
                "stabilize",
                List.of("balanced-planner", "conservative-planner"),
                new CapabilityProfile(
                        List.of("stability", "analysis", "memory", "repair"),
                        List.of("balanced", "conservative"),
                        0.72,
                        0.64,
                        0.80,
                        0.95,
                        1.05,
                        Map.of(
                                ResourceType.COMPUTE, 1.1,
                                ResourceType.MEMORY, 0.8,
                                ResourceType.TOOL_USAGE, 1.0,
                                ResourceType.AGENT_TIME, 0.9,
                                ResourceType.TASK_PRIORITY, 0.5
                        )
                ),
                150.0,
                0.66,
                Map.of(
                        ResourceType.COMPUTE, 120.0,
                        ResourceType.MEMORY, 140.0,
                        ResourceType.TOOL_USAGE, 90.0,
                        ResourceType.AGENT_TIME, 110.0,
                        ResourceType.TASK_PRIORITY, 70.0
                )
        );
        CivilizationUnit nova = createUnit(
                "nova-org",
                "Nova Exploration Labs",
                "expand",
                List.of("balanced-planner", "aggressive-planner"),
                new CapabilityProfile(
                        List.of("exploration", "generation", "code", "tooling"),
                        List.of("balanced", "aggressive"),
                        0.67,
                        0.76,
                        0.62,
                        1.05,
                        0.92,
                        Map.of(
                                ResourceType.COMPUTE, 1.0,
                                ResourceType.MEMORY, 0.9,
                                ResourceType.TOOL_USAGE, 1.2,
                                ResourceType.AGENT_TIME, 1.1,
                                ResourceType.TASK_PRIORITY, 0.6
                        )
                ),
                135.0,
                0.58,
                Map.of(
                        ResourceType.COMPUTE, 110.0,
                        ResourceType.MEMORY, 95.0,
                        ResourceType.TOOL_USAGE, 130.0,
                        ResourceType.AGENT_TIME, 135.0,
                        ResourceType.TASK_PRIORITY, 80.0
                )
        );
        CivilizationUnit helix = createUnit(
                "helix-org",
                "Helix Coordination Guild",
                "balanced",
                plannerAgentIds == null || plannerAgentIds.isEmpty()
                        ? List.of("balanced-planner", "conservative-planner", "aggressive-planner")
                        : plannerAgentIds,
                new CapabilityProfile(
                        List.of("delivery", "planning", "integration", "coordination"),
                        List.of("balanced", "conservative", "aggressive"),
                        0.75,
                        0.72,
                        0.70,
                        1.0,
                        1.0,
                        Map.of(
                                ResourceType.COMPUTE, 1.0,
                                ResourceType.MEMORY, 0.95,
                                ResourceType.TOOL_USAGE, 1.0,
                                ResourceType.AGENT_TIME, 0.95,
                                ResourceType.TASK_PRIORITY, 0.55
                        )
                ),
                145.0,
                0.61,
                Map.of(
                        ResourceType.COMPUTE, 125.0,
                        ResourceType.MEMORY, 120.0,
                        ResourceType.TOOL_USAGE, 110.0,
                        ResourceType.AGENT_TIME, 115.0,
                        ResourceType.TASK_PRIORITY, 75.0
                )
        );
        List<CivilizationUnit> units = List.of(atlas, nova, helix);
        if (economy != null) {
            economy.registerOrganization(resources == null ? "civilization-commons" : resources.commonsOwner(), new Budget(1000.0, 0.0));
            units.forEach(unit -> economy.registerOrganization(unit.orgId(), unit.budget()));
        }
        if (resources != null) {
            units.forEach(unit -> resources.registerOwner(unit.orgId(), unit.resourcePortfolio()));
            resources.registerOwner(resources.commonsOwner(), Map.of(
                    ResourceType.COMPUTE, 500.0,
                    ResourceType.MEMORY, 500.0,
                    ResourceType.TOOL_USAGE, 400.0,
                    ResourceType.AGENT_TIME, 450.0,
                    ResourceType.TASK_PRIORITY, 350.0
            ));
        }
        return new DigitalCivilization(
                "MindOS Digital Civilization",
                units,
                economy,
                rules,
                resources,
                1,
                Map.of("mode", "decentralized-market")
        );
    }

    public CivilizationUnit createUnit(String orgId,
                                       String orgName,
                                       String strategyMode,
                                       List<String> activePlanners,
                                       CapabilityProfile capability,
                                       double budgetCredits,
                                       double reputation,
                                       Map<ResourceType, Double> resources) {
        AIOrganization organization = configureOrganization(AIOrganization.bootstrap(orgName, activePlanners), strategyMode, activePlanners);
        return new CivilizationUnit(
                orgId,
                organization,
                capability,
                new Budget(budgetCredits, 0.0),
                reputation,
                resources,
                true,
                Map.of("strategyMode", strategyMode)
        );
    }

    public CivilizationUnit spawnFrom(CivilizationUnit template,
                                      String orgId,
                                      String orgName) {
        CivilizationUnit safeTemplate = template == null
                ? createUnit("spawned-org", orgName, "balanced", List.of("balanced-planner"), new CapabilityProfile(List.of("delivery"), List.of("balanced"), 0.6, 0.6, 0.6, 1.0, 1.0, Map.of()), 90.0, 0.5, Map.of(
                ResourceType.COMPUTE, 80.0,
                ResourceType.MEMORY, 80.0,
                ResourceType.TOOL_USAGE, 60.0,
                ResourceType.AGENT_TIME, 70.0,
                ResourceType.TASK_PRIORITY, 50.0
        ))
                : template;
        AIOrganization organization = configureOrganization(
                AIOrganization.bootstrap(orgName, safeTemplate.organization().activePlannerIds()),
                String.valueOf(safeTemplate.metadata().getOrDefault("strategyMode", "balanced")),
                safeTemplate.organization().activePlannerIds()
        );
        return new CivilizationUnit(
                orgId,
                organization,
                safeTemplate.capability(),
                new Budget(Math.max(80.0, safeTemplate.budget().availableCredits() * 0.75), 0.0),
                Math.max(0.45, safeTemplate.reputation() * 0.95),
                Map.of(
                        ResourceType.COMPUTE, 70.0,
                        ResourceType.MEMORY, 70.0,
                        ResourceType.TOOL_USAGE, 60.0,
                        ResourceType.AGENT_TIME, 65.0,
                        ResourceType.TASK_PRIORITY, 40.0
                ),
                true,
                Map.of("spawnedFrom", safeTemplate.orgId())
        );
    }

    private AIOrganization configureOrganization(AIOrganization organization,
                                                 String strategyMode,
                                                 List<String> activePlanners) {
        if (organization == null) {
            return AIOrganization.bootstrap("Digital Organization", activePlanners);
        }
        Department strategy = organization.strategyDept().withMetadata("strategyMode", strategyMode);
        Department planning = organization.planningDept();
        List<OrganizationAgent> nextAgents = new ArrayList<>();
        for (OrganizationAgent agent : planning.agents()) {
            if (agent == null) {
                continue;
            }
            boolean active = activePlanners == null || activePlanners.isEmpty() || activePlanners.stream().anyMatch(id -> id.equalsIgnoreCase(agent.agentId()));
            nextAgents.add(agent.withActive(active));
        }
        planning = planning.withAgents(nextAgents).withMetadata(new LinkedHashMap<>(Map.of(
                "proposalQuota", nextAgents.stream().filter(OrganizationAgent::active).count(),
                "planningBudget", nextAgents.size()
        )));
        return organization.revise(strategy, planning, organization.executionDept(), organization.evaluationDept(), Map.of(
                "strategyMode", strategyMode,
                "civilizationUnit", true
        ));
    }
}
