package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.memory.LongTaskCommandService;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class LongTaskCommandOrchestrator {

    private final LongTaskCommandService longTaskCommandService;

    public LongTaskCommandOrchestrator(LongTaskCommandService longTaskCommandService) {
        this.longTaskCommandService = longTaskCommandService;
    }

    public LongTask createTask(String userId,
                               String title,
                               String objective,
                               List<String> steps,
                               Instant dueAt,
                               Instant nextCheckAt) {
        return longTaskCommandService.createTask(userId, title, objective, steps, dueAt, nextCheckAt);
    }

    public List<LongTask> claimReadyTasks(String userId, String workerId, int limit, long leaseSeconds) {
        return longTaskCommandService.claimReadyTasks(userId, workerId, limit, leaseSeconds);
    }

    public LongTask updateProgress(String userId,
                                   String taskId,
                                   String workerId,
                                   String completedStep,
                                   String note,
                                   String blockedReason,
                                   Instant nextCheckAt,
                                   boolean markCompleted) {
        return longTaskCommandService.updateProgress(
                userId,
                taskId,
                workerId,
                completedStep,
                note,
                blockedReason,
                nextCheckAt,
                markCompleted
        );
    }

    public LongTask updateStatus(String userId,
                                 String taskId,
                                 String status,
                                 String note,
                                 Instant nextCheckAt) {
        return longTaskCommandService.updateStatus(userId, taskId, status, note, nextCheckAt);
    }
}
