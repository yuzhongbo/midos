package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.LongGoal;
import com.zhongbo.mindos.assistant.memory.model.LongGoalStatus;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LongTaskServiceTest {

    @Test
    void shouldPersistLongTasksAcrossRestarts(@TempDir Path tempDir) {
        MemoryStateStore stateStore = new FileMemoryStateStore(true, tempDir, new ObjectMapper());
        LongTaskService first = new LongTaskService(stateStore);

        LongTask created = first.createTask(
                "u1",
                "整理发布计划",
                "确保版本发布前检查完成",
                List.of("确认范围", "执行回归"),
                Instant.parse("2026-04-10T00:00:00Z"),
                Instant.parse("2026-04-03T00:00:00Z")
        );
        first.claimReadyTasks("u1", "worker-a", 1, 120);
        first.updateProgress("u1", created.taskId(), "worker-a", "确认范围", "范围已确认", "", Instant.parse("2026-04-04T00:00:00Z"), false);

        LongTaskService second = new LongTaskService(new FileMemoryStateStore(true, tempDir, new ObjectMapper()));

        LongTask restored = second.getTask("u1", created.taskId());
        assertNotNull(restored);
        assertEquals(LongTaskStatus.RUNNING, restored.status());
        assertEquals(50, restored.progressPercent());
        assertEquals(List.of("执行回归"), restored.pendingSteps());
        assertEquals(List.of("确认范围"), restored.completedSteps());
        assertEquals("worker-a", restored.leaseOwner());
        assertEquals(1, second.listTasks("u1", null).size());
    }

    @Test
    void shouldLinkTaskToGoalAndMarkGoalAchievedWhenTaskCompletes(@TempDir Path tempDir) {
        MemoryStateStore stateStore = new FileMemoryStateStore(true, tempDir, new ObjectMapper());
        LongGoalService longGoalService = new LongGoalService(stateStore);
        LongTaskService longTaskService = new LongTaskService(stateStore, longGoalService);

        LongGoal goal = longGoalService.createGoal(
                "u-goal-task",
                "Upgrade Hermes to stage5",
                "Finish goal persistence wiring",
                "Goal context is available end-to-end",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-04-20T00:00:00Z")
        );

        LongTask task = longTaskService.createTask(
                "u-goal-task",
                "Wire active goal context",
                "Inject active goal into Hermes runtime",
                List.of("finish active goal wiring"),
                Instant.parse("2026-04-25T00:00:00Z"),
                Instant.parse("2026-04-18T00:00:00Z"),
                goal.goalId()
        );

        assertEquals(goal.goalId(), task.goalId());
        assertEquals(List.of(task.taskId()), longGoalService.getGoal("u-goal-task", goal.goalId()).linkedTaskIds());

        longTaskService.claimReadyTasks("u-goal-task", "worker-a", 1, 120);
        LongTask completed = longTaskService.updateProgress(
                "u-goal-task",
                task.taskId(),
                "worker-a",
                "finish active goal wiring",
                "done",
                "",
                Instant.parse("2026-04-18T12:00:00Z"),
                true
        );

        assertEquals(LongTaskStatus.COMPLETED, completed.status());
        LongGoal updatedGoal = longGoalService.getGoal("u-goal-task", goal.goalId());
        assertEquals(LongGoalStatus.ACHIEVED, updatedGoal.status());
        assertEquals(100, updatedGoal.progressPercent());
    }
}
