package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record PlanPath(List<PlanNode> nodes,
                       double score,
                       double pathCost,
                       List<String> reasons) {

    public PlanPath {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static PlanPath seed(PlanNode node) {
        return new PlanPath(List.of(node), node.totalScore(), node.pathCost(), node.reasons());
    }

    public String lastSkill() {
        return nodes.isEmpty() ? "" : nodes.get(nodes.size() - 1).skillName();
    }

    public List<String> skills() {
        return nodes.stream().map(PlanNode::skillName).toList();
    }

    public boolean containsSkill(String skillName) {
        return nodes.stream().anyMatch(node -> node.skillName().equals(skillName));
    }

    public PlanPath extend(PlanNode node) {
        List<PlanNode> nextNodes = new ArrayList<>(nodes);
        nextNodes.add(node);
        Set<String> mergedReasons = new LinkedHashSet<>(reasons);
        mergedReasons.addAll(node.reasons());
        return new PlanPath(
                nextNodes,
                score + node.totalScore(),
                pathCost + node.pathCost(),
                List.copyOf(mergedReasons)
        );
    }
}
