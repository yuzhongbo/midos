package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.LongGoal;
import com.zhongbo.mindos.assistant.memory.model.LongGoalStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@Service
public class LongGoalCommandService {

    private final MemoryFacade memoryFacade;

    @Autowired
    public LongGoalCommandService(MemoryFacade memoryFacade) {
        this.memoryFacade = memoryFacade;
    }

    public LongGoal createGoal(String userId,
                               String title,
                               String objective,
                               String successCriteria,
                               Instant dueAt,
                               Instant nextReviewAt) {
        return memoryFacade.createLongGoal(userId, title, objective, successCriteria, dueAt, nextReviewAt);
    }

    public LongGoal updateStatus(String userId,
                                 String goalId,
                                 String status,
                                 String note,
                                 Instant nextReviewAt) {
        return updateStatus(userId, goalId, parseStatus(status), note, nextReviewAt);
    }

    public LongGoal updateStatus(String userId,
                                 String goalId,
                                 LongGoalStatus status,
                                 String note,
                                 Instant nextReviewAt) {
        return memoryFacade.updateLongGoalStatus(userId, goalId, status, note, nextReviewAt);
    }

    private LongGoalStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return LongGoalStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid goal status: " + status, ex);
        }
    }
}
