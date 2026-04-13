package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.util.LinkedHashMap;
import java.util.Map;

public record ResourcePool(ResourceType type,
                           double capacity,
                           Map<String, Double> balances) {

    public ResourcePool {
        type = type == null ? ResourceType.COMPUTE : type;
        capacity = Math.max(0.0, capacity);
        balances = balances == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(balances));
    }

    public double balanceOf(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return 0.0;
        }
        return clamp(balances.getOrDefault(ownerId, 0.0));
    }

    public double totalAllocated() {
        return balances.values().stream()
                .mapToDouble(ResourcePool::clamp)
                .sum();
    }

    public ResourcePool withBalance(String ownerId, double balance) {
        LinkedHashMap<String, Double> nextBalances = new LinkedHashMap<>(balances);
        nextBalances.put(ownerId == null ? "" : ownerId.trim(), clamp(balance));
        return new ResourcePool(type, Math.max(capacity, totalAllocated()), nextBalances);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}
