package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.AutonomousPlanningContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class StrategyDepartmentService {

    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s*(?:然后|then|and|并且|->|;|；|,|，|。)\\s*");

    public StrategyDirective define(Goal goal,
                                    AIOrganization organization,
                                    OrgMemory orgMemory,
                                    AutonomousPlanningContext context) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        AIOrganization safeOrganization = organization == null
                ? AIOrganization.bootstrap("MindOS Organization", List.of())
                : organization;
        String baseMode = safeOrganization.strategyDept() == null
                ? "balanced"
                : safeOrganization.strategyDept().stringMetadata("strategyMode", "balanced");
        double historicalSuccess = orgMemory == null ? 0.5 : orgMemory.averageSuccessRate();
        String planningMode = historicalSuccess < 0.45 ? "stabilize" : baseMode;
        List<String> focusAreas = extractFocusAreas(safeGoal.description());
        Map<String, Integer> resourceAllocation = Map.of(
                "strategy", safeOrganization.strategyDept() == null ? 0 : safeOrganization.strategyDept().activeHeadcount(),
                "planning", safeOrganization.planningDept() == null ? 0 : safeOrganization.planningDept().activeHeadcount(),
                "execution", safeOrganization.executionDept() == null ? 0 : safeOrganization.executionDept().activeHeadcount(),
                "evaluation", safeOrganization.evaluationDept() == null ? 0 : safeOrganization.evaluationDept().activeHeadcount()
        );
        LinkedHashMap<String, Object> profileContext = new LinkedHashMap<>();
        profileContext.put("orgName", safeOrganization.orgName());
        profileContext.put("orgRevision", safeOrganization.revision());
        profileContext.put("orgStrategyMode", planningMode);
        profileContext.put("orgFocusAreas", focusAreas);
        profileContext.put("orgResourceAllocation", resourceAllocation);
        profileContext.put("orgHistoricalSuccess", historicalSuccess);
        if (context != null && context.profileContext() != null && !context.profileContext().isEmpty()) {
            profileContext.putAll(context.profileContext());
        }
        return new StrategyDirective(
                safeGoal,
                focusAreas.isEmpty() ? safeGoal.description() : focusAreas.get(0),
                planningMode,
                resourceAllocation,
                focusAreas,
                profileContext
        );
    }

    private List<String> extractFocusAreas(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> focusAreas = new LinkedHashSet<>();
        for (String part : SPLIT_PATTERN.split(description.trim())) {
            if (part == null) {
                continue;
            }
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                focusAreas.add(normalized);
            }
            if (focusAreas.size() >= 4) {
                break;
            }
        }
        return focusAreas.isEmpty() ? List.of(description.trim()) : List.copyOf(focusAreas);
    }
}
