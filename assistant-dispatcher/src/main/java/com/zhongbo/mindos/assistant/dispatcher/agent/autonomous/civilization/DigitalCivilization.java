package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record DigitalCivilization(String civilizationName,
                                  List<CivilizationUnit> organizations,
                                  EconomicSystem economy,
                                  RuleSystem rules,
                                  ResourceSystem resources,
                                  int epoch,
                                  Map<String, Object> metadata) {

    public DigitalCivilization {
        civilizationName = civilizationName == null || civilizationName.isBlank() ? "Digital Civilization" : civilizationName.trim();
        organizations = organizations == null ? List.of() : List.copyOf(organizations);
        epoch = Math.max(1, epoch);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public Optional<CivilizationUnit> unit(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Optional.empty();
        }
        return organizations.stream()
                .filter(unit -> unit != null && orgId.equalsIgnoreCase(unit.orgId()))
                .findFirst();
    }

    public DigitalCivilization withOrganizations(List<CivilizationUnit> nextOrganizations) {
        return new DigitalCivilization(civilizationName, nextOrganizations, economy, rules, resources, epoch + 1, metadata);
    }

    public DigitalCivilization withMetadata(Map<String, Object> additions) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata);
        if (additions != null) {
            merged.putAll(additions);
        }
        return new DigitalCivilization(civilizationName, organizations, economy, rules, resources, epoch + 1, merged);
    }
}
