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

class LongGoalServiceTest {

    @Test
    void shouldPersistGoalsAndPickEarliestActiveDueDate(@TempDir Path tempDir) {
        MemoryStateStore stateStore = new FileMemoryStateStore(true, tempDir, new ObjectMapper());
        LongGoalService first = new LongGoalService(stateStore);

        first.createGoal(
                "u1",
                "Later goal",
                "finish later",
                "",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-04-25T00:00:00Z")
        );
        LongGoal earlier = first.createGoal(
                "u1",
                "Earlier goal",
                "finish sooner",
                "",
                Instant.parse("2026-04-20T00:00:00Z"),
                Instant.parse("2026-04-18T00:00:00Z")
        );

        LongGoalService second = new LongGoalService(new FileMemoryStateStore(true, tempDir, new ObjectMapper()));

        assertEquals(2, second.listGoals("u1", null).size());
        assertNotNull(second.activeGoal("u1"));
        assertEquals(earlier.goalId(), second.activeGoal("u1").goalId());
    }

    @Test
    void shouldSyncGoalProgressFromLinkedTasks() {
        LongGoalService service = new LongGoalService(MemoryStateStore.noOp());
        LongGoal goal = service.createGoal(
                "u-sync",
                "Upgrade Hermes",
                "Finish goal persistence",
                "Goal context is reusable",
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-04-20T00:00:00Z")
        );

        Instant now = Instant.parse("2026-04-17T00:00:00Z");
        LongTask firstTask = new LongTask(
                "task-1",
                "u-sync",
                "Wire context",
                "",
                goal.goalId(),
                LongTaskStatus.RUNNING,
                50,
                List.of("finalize"),
                List.of("start"),
                List.of(),
                "",
                now,
                now,
                now,
                now,
                "",
                null
        );
        LongTask secondTask = new LongTask(
                "task-2",
                "u-sync",
                "Validate flow",
                "",
                goal.goalId(),
                LongTaskStatus.COMPLETED,
                100,
                List.of(),
                List.of("validate"),
                List.of(),
                "",
                now,
                now,
                now,
                now,
                "",
                null
        );

        LongGoal synced = service.syncWithTasks("u-sync", goal.goalId(), List.of(firstTask, secondTask));

        assertEquals(LongGoalStatus.ACTIVE, synced.status());
        assertEquals(75, synced.progressPercent());
        assertEquals(List.of("task-1", "task-2"), synced.linkedTaskIds());
    }
}
