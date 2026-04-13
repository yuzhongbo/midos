package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization.OrganizationCycleResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class CivilizationMemory {

    private final CopyOnWriteArrayList<CivilizationTrace> traces = new CopyOnWriteArrayList<>();

    public CivilizationTrace record(DigitalCivilization civilizationBefore,
                                    DigitalCivilization civilizationAfter,
                                    CivilizationAssignment assignment,
                                    OrganizationCycleResult organizationCycle,
                                    Map<String, Double> reputationSnapshot,
                                    Map<String, Map<ResourceType, Double>> resourceSnapshot) {
        CivilizationTrace trace = new CivilizationTrace(
                assignment == null || assignment.request() == null || assignment.request().goal() == null ? "" : assignment.request().goal().goalId(),
                civilizationBefore == null ? 1 : civilizationBefore.epoch(),
                civilizationAfter == null ? 1 : civilizationAfter.epoch(),
                assignment == null ? "" : assignment.selectedOrgId(),
                assignment == null ? List.of() : assignment.offers(),
                assignment == null ? null : assignment.transaction(),
                organizationCycle,
                reputationSnapshot == null ? Map.of() : Map.copyOf(reputationSnapshot),
                resourceSnapshot == null ? Map.of() : Map.copyOf(resourceSnapshot),
                Instant.now()
        );
        traces.add(trace);
        return trace;
    }

    public List<CivilizationTrace> traces() {
        List<CivilizationTrace> snapshot = new ArrayList<>(traces);
        snapshot.sort(Comparator.comparing(CivilizationTrace::recordedAt));
        return List.copyOf(snapshot);
    }

    public List<CivilizationTrace> recent(int limit) {
        List<CivilizationTrace> snapshot = traces();
        if (limit <= 0 || snapshot.size() <= limit) {
            return snapshot;
        }
        return List.copyOf(snapshot.subList(snapshot.size() - limit, snapshot.size()));
    }

    public double rejectionRate() {
        List<CivilizationTrace> recent = recent(16);
        if (recent.isEmpty()) {
            return 0.0;
        }
        return recent.stream()
                .filter(trace -> trace.transaction() != null && trace.transaction().rejected())
                .count() / (double) recent.size();
    }

    public double marketLoad() {
        return recent(12).stream()
                .mapToDouble(trace -> trace.offers().size() / 5.0)
                .average()
                .orElse(0.0);
    }

    public double averageSuccess() {
        return recent(12).stream()
                .mapToDouble(trace -> trace.success() ? 1.0 : trace.partial() ? 0.5 : 0.0)
                .average()
                .orElse(0.5);
    }

    public record CivilizationTrace(String goalId,
                                    int epochBefore,
                                    int epochAfter,
                                    String selectedOrgId,
                                    List<Offer> offers,
                                    Transaction transaction,
                                    OrganizationCycleResult organizationCycle,
                                    Map<String, Double> reputationSnapshot,
                                    Map<String, Map<ResourceType, Double>> resourceSnapshot,
                                    Instant recordedAt) {

        public CivilizationTrace {
            goalId = goalId == null ? "" : goalId.trim();
            selectedOrgId = selectedOrgId == null ? "" : selectedOrgId.trim();
            offers = offers == null ? List.of() : List.copyOf(offers);
            reputationSnapshot = reputationSnapshot == null ? Map.of() : Map.copyOf(reputationSnapshot);
            resourceSnapshot = resourceSnapshot == null ? Map.of() : Map.copyOf(resourceSnapshot);
            recordedAt = recordedAt == null ? Instant.now() : recordedAt;
        }

        public boolean success() {
            return organizationCycle != null
                    && organizationCycle.assessment() != null
                    && organizationCycle.assessment().evaluation() != null
                    && organizationCycle.assessment().evaluation().isSuccess();
        }

        public boolean partial() {
            return organizationCycle != null
                    && organizationCycle.assessment() != null
                    && organizationCycle.assessment().evaluation() != null
                    && organizationCycle.assessment().evaluation().isPartial();
        }
    }
}
