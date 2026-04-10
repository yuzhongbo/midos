package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Service
public class SkillCostTelemetryService implements SkillCostTelemetry {

    private final int maxRecentCalls;
    private final Map<String, ConcurrentLinkedDeque<SkillCostSample>> samplesByUser = new ConcurrentHashMap<>();

    public SkillCostTelemetryService(@Value("${mindos.skill.cost-telemetry.max-recent-calls:500}") int maxRecentCalls) {
        this.maxRecentCalls = Math.max(50, maxRecentCalls);
    }

    @Override
    public void record(String userId, String skillName, long latencyMs, int totalTokensEstimate, boolean success) {
        if (userId == null || userId.isBlank() || skillName == null || skillName.isBlank()) {
            return;
        }
        ConcurrentLinkedDeque<SkillCostSample> deque = samplesByUser.computeIfAbsent(userId.trim(), ignored -> new ConcurrentLinkedDeque<>());
        deque.addLast(new SkillCostSample(
                skillName.trim(),
                Math.max(0L, latencyMs),
                Math.max(0, totalTokensEstimate),
                success,
                Instant.now()
        ));
        while (deque.size() > maxRecentCalls) {
            deque.pollFirst();
        }
    }

    @Override
    public Map<String, CostModel> costModels(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        ConcurrentLinkedDeque<SkillCostSample> deque = samplesByUser.get(userId.trim());
        if (deque == null || deque.isEmpty()) {
            return Map.of();
        }
        List<SkillCostSample> snapshot = new ArrayList<>(deque);
        Map<String, List<SkillCostSample>> grouped = snapshot.stream()
                .collect(Collectors.groupingBy(
                        SkillCostSample::skillName,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        if (grouped.isEmpty()) {
            return Map.of();
        }

        Map<String, SkillCostAggregate> aggregates = new LinkedHashMap<>();
        double maxAverageTokens = 0.0;
        double maxAverageLatency = 0.0;
        for (Map.Entry<String, List<SkillCostSample>> entry : grouped.entrySet()) {
            SkillCostAggregate aggregate = aggregate(entry.getValue());
            aggregates.put(entry.getKey(), aggregate);
            maxAverageTokens = Math.max(maxAverageTokens, aggregate.averageTokens());
            maxAverageLatency = Math.max(maxAverageLatency, aggregate.averageLatency());
        }

        final double tokenDivisor = maxAverageTokens;
        final double latencyDivisor = maxAverageLatency;
        Map<String, CostModel> models = new LinkedHashMap<>();
        aggregates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    SkillCostAggregate aggregate = entry.getValue();
                    double tokenCost = normalize(aggregate.averageTokens(), tokenDivisor);
                    double latency = normalize(aggregate.averageLatency(), latencyDivisor);
                    models.put(entry.getKey(), new CostModel(tokenCost, latency, aggregate.successRate()));
                });
        return Map.copyOf(models);
    }

    private SkillCostAggregate aggregate(List<SkillCostSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return new SkillCostAggregate(0.0, 0.0, 0.5);
        }
        long count = samples.size();
        double averageTokens = samples.stream().mapToLong(SkillCostSample::totalTokensEstimate).average().orElse(0.0);
        double averageLatency = samples.stream().mapToLong(SkillCostSample::latencyMs).average().orElse(0.0);
        double successRate = samples.stream().filter(SkillCostSample::success).count() / (double) count;
        return new SkillCostAggregate(averageTokens, averageLatency, successRate);
    }

    private double normalize(double value, double maxValue) {
        if (maxValue <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value / maxValue));
    }

    private record SkillCostSample(String skillName,
                                   long latencyMs,
                                   int totalTokensEstimate,
                                   boolean success,
                                   Instant createdAt) {
    }

    private record SkillCostAggregate(double averageTokens,
                                      double averageLatency,
                                      double successRate) {
    }
}
