package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResourceSystem {

    private static final String COMMONS_OWNER = "civilization-commons";
    private final Map<ResourceType, ResourcePool> pools = new ConcurrentHashMap<>();

    public ResourceSystem() {
        initializeCommons();
    }

    public void registerOwner(String ownerId, Map<ResourceType, Double> initialBalances) {
        if (ownerId == null || ownerId.isBlank()) {
            return;
        }
        for (ResourceType type : ResourceType.values()) {
            ResourcePool pool = pools.getOrDefault(type, new ResourcePool(type, defaultCapacity(type), Map.of()));
            double balance = initialBalances == null ? 0.0 : initialBalances.getOrDefault(type, 0.0);
            pools.put(type, pool.withBalance(ownerId.trim(), balance));
        }
    }

    public void transfer(Resource from, Resource to, double amount) {
        if (from == null || to == null || from.type() != to.type()) {
            return;
        }
        double safeAmount = clamp(amount);
        if (safeAmount <= 0.0) {
            return;
        }
        double available = balanceOf(from.ownerId(), from.type());
        if (available < safeAmount) {
            safeAmount = available;
        }
        ResourcePool pool = pools.get(from.type());
        if (pool == null) {
            return;
        }
        pools.put(from.type(), pool
                .withBalance(from.ownerId(), available - safeAmount)
                .withBalance(to.ownerId(), balanceOf(to.ownerId(), to.type()) + safeAmount));
    }

    public void consume(String ownerId, Map<ResourceType, Double> required) {
        if (ownerId == null || ownerId.isBlank() || required == null || required.isEmpty()) {
            return;
        }
        for (Map.Entry<ResourceType, Double> entry : required.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            ResourcePool pool = pools.get(entry.getKey());
            if (pool == null) {
                continue;
            }
            double nextBalance = Math.max(0.0, pool.balanceOf(ownerId) - clamp(entry.getValue()));
            pools.put(entry.getKey(), pool.withBalance(ownerId, nextBalance));
        }
    }

    public boolean hasResources(String ownerId, Map<ResourceType, Double> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (Map.Entry<ResourceType, Double> entry : required.entrySet()) {
            if (balanceOf(ownerId, entry.getKey()) + 1e-9 < clamp(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public double balanceOf(String ownerId, ResourceType type) {
        ResourcePool pool = pools.get(type == null ? ResourceType.COMPUTE : type);
        return pool == null ? 0.0 : pool.balanceOf(ownerId);
    }

    public Map<ResourceType, Double> balancesOf(String ownerId) {
        Map<ResourceType, Double> balances = new EnumMap<>(ResourceType.class);
        for (ResourceType type : ResourceType.values()) {
            balances.put(type, balanceOf(ownerId, type));
        }
        return Map.copyOf(balances);
    }

    public Map<ResourceType, ResourcePool> pools() {
        return Map.copyOf(pools);
    }

    public String commonsOwner() {
        return COMMONS_OWNER;
    }

    public void redistribute(List<CivilizationUnit> units, Map<String, Double> reputationScores) {
        if (units == null || units.isEmpty()) {
            return;
        }
        for (ResourceType type : List.of(ResourceType.COMPUTE, ResourceType.MEMORY, ResourceType.AGENT_TIME)) {
            for (CivilizationUnit unit : units) {
                if (unit == null || !unit.active()) {
                    continue;
                }
                double target = 60.0 + 50.0 * reputationScores.getOrDefault(unit.orgId(), unit.reputation());
                double current = balanceOf(unit.orgId(), type);
                if (current < target) {
                    transfer(new Resource(COMMONS_OWNER, type), new Resource(unit.orgId(), type), Math.min(target - current, balanceOf(COMMONS_OWNER, type)));
                }
            }
        }
    }

    private void initializeCommons() {
        for (ResourceType type : ResourceType.values()) {
            pools.put(type, new ResourcePool(type, defaultCapacity(type), Map.of(COMMONS_OWNER, defaultCapacity(type) * 0.8)));
        }
    }

    private double defaultCapacity(ResourceType type) {
        return switch (type == null ? ResourceType.COMPUTE : type) {
            case COMPUTE -> 1000.0;
            case MEMORY -> 900.0;
            case TOOL_USAGE -> 700.0;
            case AGENT_TIME -> 800.0;
            case TASK_PRIORITY -> 500.0;
        };
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }
}
