package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.memory.LongGoalCommandService;
import com.zhongbo.mindos.assistant.memory.model.LongGoal;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LongGoalCommandOrchestrator {

    private final LongGoalCommandService longGoalCommandService;

    public LongGoalCommandOrchestrator(LongGoalCommandService longGoalCommandService) {
        this.longGoalCommandService = longGoalCommandService;
    }

    public LongGoal createGoal(String userId,
                               String title,
                               String objective,
                               String successCriteria,
                               Instant dueAt,
                               Instant nextReviewAt) {
        return longGoalCommandService.createGoal(userId, title, objective, successCriteria, dueAt, nextReviewAt);
    }

    public LongGoal updateStatus(String userId,
                                 String goalId,
                                 String status,
                                 String note,
                                 Instant nextReviewAt) {
        return longGoalCommandService.updateStatus(userId, goalId, status, note, nextReviewAt);
    }
}
