package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.market;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.Agent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentResponse;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentRole;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentTaskType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class DefaultAgentMarket implements AgentMarket {

    @Override
    public AgentMarketResult compete(AgentMarketRequest request) {
        AgentMarketRequest safeRequest = request == null
                ? new AgentMarketRequest(AgentMessage.of("", "", null), emptyContext(), List.of(), "")
                : request;
        AgentMessage message = safeMessage(safeRequest.message());
        AgentContext context = safeContext(safeRequest.context(), message, safeRequest.userFeedback());
        List<Agent> contestants = safeRequest.contestants();

        if (contestants.isEmpty()) {
            return AgentMarketResult.empty(message, context, "no contestants");
        }

        List<AgentMarketProposal> ranked = new ArrayList<>();
        for (Agent agent : contestants) {
            if (agent == null || !agent.supports(message)) {
                continue;
            }
            AgentResponse response = safeResponse(agent.handle(message, context), agent);
            ranked.add(scoreProposal(agent, response, message, context, safeRequest.userFeedback()));
        }

        if (ranked.isEmpty()) {
            return AgentMarketResult.empty(message, context, "no eligible contestants");
        }

        ranked.sort(Comparator
                .comparingDouble(AgentMarketProposal::score).reversed()
                .thenComparing(AgentMarketProposal::terminal, Comparator.reverseOrder())
                .thenComparing(AgentMarketProposal::success, Comparator.reverseOrder())
                .thenComparing(AgentMarketProposal::agentName));

        List<AgentMarketProposal> numbered = new ArrayList<>();
        int rank = 1;
        for (AgentMarketProposal proposal : ranked) {
            numbered.add(new AgentMarketProposal(
                    proposal.agentName(),
                    proposal.role(),
                    proposal.response(),
                    proposal.score(),
                    withRankReason(proposal.reasons(), rank++)
            ));
        }

        AgentMarketProposal winner = numbered.get(0);
        String summary = "winner=" + winner.agentName()
                + ",score=" + round(winner.score())
                + ",contestants=" + numbered.size()
                + ",taskType=" + (message.type() == null ? "unknown" : message.type().name());
        return new AgentMarketResult(
                message.taskId(),
                message.type(),
                message,
                context,
                winner,
                List.copyOf(numbered),
                Instant.now(),
                summary
        );
    }

    private AgentMarketProposal scoreProposal(Agent agent,
                                              AgentResponse response,
                                              AgentMessage message,
                                              AgentContext context,
                                              String userFeedback) {
        AgentResponse safeResponse = response == null
                ? AgentResponse.completed(
                        agent.name(),
                        SkillResult.failure(agent.name(), "no response"),
                        false,
                        List.of(),
                        Map.of(),
                        "no response"
                )
                : response;

        List<String> reasons = new ArrayList<>();
        double score = 0.15;
        if (safeResponse.result() == null) {
            score += 0.20;
            reasons.add("proposal");
        } else if (safeResponse.result().success()) {
            score += 0.55;
            reasons.add("result-success");
        } else {
            score += 0.05;
            reasons.add("result-failure");
        }

        if (safeResponse.terminal()) {
            score += 0.10;
            reasons.add("terminal");
        }

        if (safeResponse.usedFallback()) {
            score -= 0.20;
            reasons.add("fallback");
        }

        int outboundCount = safeResponse.outboundMessages().size();
        if (outboundCount > 0) {
            score += Math.min(0.15, outboundCount * 0.04);
            reasons.add("outbound=" + outboundCount);
        }

        int patchCount = safeResponse.contextPatch().size();
        if (patchCount > 0) {
            score += Math.min(0.15, patchCount * 0.03);
            reasons.add("patch=" + patchCount);
        }

        if (!safeResponse.summary().isBlank()) {
            score += Math.min(0.08, safeResponse.summary().length() / 200.0);
            reasons.add("summary");
        }

        AgentTaskType taskType = message == null ? null : message.type();
        if (taskType == AgentTaskType.PLAN_REQUEST) {
            if (safeResponse.result() == null) {
                score += 0.05;
                reasons.add("plan-proposal");
            }
            if (!safeResponse.terminal()) {
                score += 0.03;
                reasons.add("nonterminal-plan");
            }
        } else if (taskType == AgentTaskType.EXECUTE_GRAPH || taskType == AgentTaskType.TOOL_CALL) {
            if (safeResponse.result() != null && safeResponse.result().success()) {
                score += 0.10;
                reasons.add("execution-success");
            } else if (!safeResponse.terminal()) {
                score -= 0.05;
                reasons.add("execution-not-terminal");
            }
        } else if (taskType == AgentTaskType.MEMORY_READ || taskType == AgentTaskType.MEMORY_WRITE) {
            if (!safeResponse.terminal()) {
                score += 0.02;
                reasons.add("memory-stream");
            }
        }

        if (safeResponse.result() != null && !safeResponse.result().success()) {
            score -= 0.10;
        }

        if (safeResponse.result() == null && outboundCount == 0 && patchCount == 0) {
            score -= 0.20;
            reasons.add("empty-proposal");
        }

        if (!normalize(userFeedback).isBlank()) {
            String feedback = normalize(userFeedback).toLowerCase(Locale.ROOT);
            if (containsAny(feedback, "快", "fast", "speed")) {
                score += 0.03;
                reasons.add("feedback-speed");
            }
            if (containsAny(feedback, "准", "accurate", "quality")) {
                score += 0.03;
                reasons.add("feedback-quality");
            }
            if (containsAny(feedback, "稳", "stable")) {
                score += 0.02;
                reasons.add("feedback-stability");
            }
        }

        if (context != null && context.memorySnapshot().isPresent() && !context.memorySnapshot().get().isEmpty()) {
            score += 0.03;
            reasons.add("memory-context");
        }

        return new AgentMarketProposal(agent.name(), agent.role(), safeResponse, clamp(score), reasons);
    }

    private AgentResponse safeResponse(AgentResponse response, Agent agent) {
        if (response != null) {
            return response;
        }
        return AgentResponse.completed(
                agent == null ? "" : agent.name(),
                SkillResult.failure(agent == null ? "agent" : agent.name(), "no response"),
                false,
                List.of(),
                Map.of(),
                "no response"
        );
    }

    private AgentMessage safeMessage(AgentMessage message) {
        return message == null ? AgentMessage.of("", "", null) : message;
    }

    private AgentContext safeContext(AgentContext context, AgentMessage message, String userFeedback) {
        if (context != null) {
            return context;
        }
        Map<String, Object> sharedState = new LinkedHashMap<>();
        if (userFeedback != null && !userFeedback.isBlank()) {
            sharedState.put("agentMarket.userFeedback", userFeedback.trim());
        }
        return new AgentContext(
                message == null ? "" : message.taskId(),
                null,
                null,
                sharedState,
                null
        );
    }

    private AgentContext emptyContext() {
        return new AgentContext("", null, null, Map.of(), null);
    }

    private List<String> withRankReason(List<String> reasons, int rank) {
        List<String> merged = new ArrayList<>();
        if (reasons != null) {
            merged.addAll(reasons);
        }
        merged.add("rank=" + rank);
        return List.copyOf(merged);
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
