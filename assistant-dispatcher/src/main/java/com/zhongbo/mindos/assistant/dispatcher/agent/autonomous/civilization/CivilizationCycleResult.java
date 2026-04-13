package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.AIOrganization;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationCycleResult;

public record CivilizationCycleResult(DigitalCivilization civilizationBefore,
                                      DigitalCivilization civilizationAfter,
                                      CivilizationAssignment assignment,
                                      OrganizationCycleResult organizationCycle,
                                      CivilizationMemory.CivilizationTrace trace) {

    public boolean assigned() {
        return assignment != null && assignment.assigned();
    }

    public String selectedOrgId() {
        return assignment == null ? "" : assignment.selectedOrgId();
    }

    public AIOrganization selectedOrganization() {
        return organizationCycle == null ? null : organizationCycle.organizationAfter();
    }

    public com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.worldmodel.WorldMemory.ExecutionTrace worldTrace() {
        return organizationCycle == null ? null : organizationCycle.worldTrace();
    }

    public com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrgMemory.OrgExecutionTrace orgTrace() {
        return organizationCycle == null ? null : organizationCycle.orgTrace();
    }
}
