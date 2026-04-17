package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.common.dto.LongGoalCreateRequestDto;
import com.zhongbo.mindos.assistant.common.dto.LongGoalDto;
import com.zhongbo.mindos.assistant.common.dto.LongGoalStatusUpdateDto;
import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.LongGoal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
public class LongGoalController {

    private final MemoryFacade memoryFacade;
    private final LongGoalCommandOrchestrator longGoalCommandOrchestrator;

    public LongGoalController(MemoryFacade memoryFacade,
                              LongGoalCommandOrchestrator longGoalCommandOrchestrator) {
        this.memoryFacade = memoryFacade;
        this.longGoalCommandOrchestrator = longGoalCommandOrchestrator;
    }

    @PostMapping("/{userId}")
    public LongGoalDto createGoal(@PathVariable String userId,
                                  @RequestBody LongGoalCreateRequestDto request) {
        try {
            LongGoal created = longGoalCommandOrchestrator.createGoal(
                    userId,
                    request == null ? null : request.title(),
                    request == null ? null : request.objective(),
                    request == null ? null : request.successCriteria(),
                    request == null ? null : request.dueAt(),
                    request == null ? null : request.nextReviewAt()
            );
            return toDto(created);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "invalid goal command"
                    : ex.getMessage();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message, ex);
        }
    }

    @GetMapping("/{userId}")
    public List<LongGoalDto> listGoals(@PathVariable String userId,
                                       @RequestParam(required = false) String status) {
        return memoryFacade.listLongGoals(userId, status).stream().map(this::toDto).toList();
    }

    @GetMapping("/{userId}/{goalId}")
    public LongGoalDto getGoal(@PathVariable String userId,
                               @PathVariable String goalId) {
        LongGoal goal = memoryFacade.getLongGoal(userId, goalId);
        if (goal == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "goal not found");
        }
        return toDto(goal);
    }

    @PostMapping("/{userId}/{goalId}/status")
    public LongGoalDto updateStatus(@PathVariable String userId,
                                    @PathVariable String goalId,
                                    @RequestBody LongGoalStatusUpdateDto request) {
        try {
            LongGoal updated = longGoalCommandOrchestrator.updateStatus(
                    userId,
                    goalId,
                    request == null ? null : request.status(),
                    request == null ? null : request.note(),
                    request == null ? null : request.nextReviewAt()
            );
            if (updated == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "goal not found");
            }
            return toDto(updated);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "invalid goal command"
                    : ex.getMessage();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message, ex);
        }
    }

    private LongGoalDto toDto(LongGoal goal) {
        return new LongGoalDto(
                goal.goalId(),
                goal.userId(),
                goal.title(),
                goal.objective(),
                goal.successCriteria(),
                goal.status().name(),
                goal.progressPercent(),
                goal.linkedTaskIds(),
                goal.recentNotes(),
                goal.createdAt(),
                goal.updatedAt(),
                goal.dueAt(),
                goal.nextReviewAt()
        );
    }
}
