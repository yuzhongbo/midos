package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;

import java.util.Map;

public interface DecisionOrchestrator {

    OrchestrationOutcome handle(UserInput input);

    OrchestrationOutcome orchestrate(Decision decision, OrchestrationRequest request);

    default OrchestrationOutcome fastPath(Decision decision, OrchestrationRequest request) {
        return orchestrate(decision, request);
    }

    default OrchestrationOutcome slowPath(Decision decision, OrchestrationRequest request) {
        return orchestrate(decision, request);
    }

    void recordOutcome(String userId, String userInput, SkillResult result, ExecutionTraceDto trace);

    default void commitMemoryWrites(String userId, MemoryWriteBatch batch) {
    }

    record OrchestrationOutcome(SkillResult result,
                                SkillDsl skillDsl,
                                SkillResult clarification,
                                ExecutionTraceDto trace,
                                String selectedSkill,
                                boolean usedFallback) {
        public boolean hasResult() {
            return result != null;
        }

        public boolean hasClarification() {
            return clarification != null;
        }

        public boolean hasSkillDsl() {
            return skillDsl != null;
        }
    }

    record OrchestrationRequest(String userId,
                                String userInput,
                                SkillContext skillContext,
                                Map<String, Object> profileContext) {
        public Map<String, Object> safeProfileContext() {
            return profileContext == null ? Map.of() : profileContext;
        }
    }

    record UserInput(String userId,
                     String userInput,
                     SkillContext skillContext,
                     Map<String, Object> profileContext) {
        public UserInput {
            userId = normalize(userId);
            userInput = normalize(userInput);
            skillContext = normalizeContext(skillContext, userId, userInput);
            profileContext = profileContext == null ? Map.of() : Map.copyOf(profileContext);
        }

        public static UserInput empty() {
            return new UserInput("", "", null, Map.of());
        }

        public static UserInput safe(UserInput input) {
            return input == null ? empty() : input;
        }

        public static UserInput from(OrchestrationRequest request) {
            if (request == null) {
                return empty();
            }
            return new UserInput(
                    request.userId(),
                    request.userInput(),
                    request.skillContext(),
                    request.safeProfileContext()
            );
        }

        public OrchestrationRequest toRequest() {
            return new OrchestrationRequest(userId, userInput, skillContext, profileContext);
        }

        private static SkillContext normalizeContext(SkillContext context, String userId, String userInput) {
            if (context == null) {
                return new SkillContext(userId, userInput, Map.of());
            }
            return new SkillContext(
                    normalize(context.userId()).isBlank() ? userId : normalize(context.userId()),
                    normalize(context.input()).isBlank() ? userInput : normalize(context.input()),
                    context.attributes() == null ? Map.of() : Map.copyOf(context.attributes())
            );
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
