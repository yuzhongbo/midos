package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.api.testsupport.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LongTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateClaimAndProgressLongTask() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-long-1");
        String createPayload = "{" +
                "\"title\":\"发布新版本\"," +
                "\"objective\":\"三天内完成发布\"," +
                "\"steps\":[\"准备 changelog\",\"灰度发布\",\"全量发布\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingSteps.length()").value(3))
                .andReturn();

        String taskId = ApiTestSupport.readString(created, "$.taskId");

        mockMvc.perform(post("/api/tasks/" + userId + "/claim")
                        .param("workerId", "worker-a")
                        .param("limit", "1")
                        .param("leaseSeconds", "600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(taskId))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].leaseOwner").value("worker-a"));

        String progressPayload = "{" +
                "\"workerId\":\"worker-a\"," +
                "\"completedStep\":\"准备 changelog\"," +
                "\"note\":\"day1 完成准备\"" +
                "}";

        mockMvc.perform(post("/api/tasks/" + userId + "/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedSteps.length()").value(1))
                .andExpect(jsonPath("$.pendingSteps.length()").value(2))
                .andExpect(jsonPath("$.progressPercent", greaterThan(0)));
    }

    @Test
    void shouldRejectProgressFromDifferentWorkerLeaseOwner() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-long-2");
        String createPayload = "{" +
                "\"title\":\"跨天任务\"," +
                "\"steps\":[\"step-1\",\"step-2\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = ApiTestSupport.readString(created, "$.taskId");

        mockMvc.perform(post("/api/tasks/" + userId + "/claim")
                        .param("workerId", "worker-a")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(taskId));

        String invalidUpdatePayload = "{" +
                "\"workerId\":\"worker-b\"," +
                "\"completedStep\":\"step-1\"" +
                "}";

        mockMvc.perform(post("/api/tasks/" + userId + "/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidUpdatePayload))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/tasks/" + userId + "/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedSteps.length()").value(0));
    }

    @Test
    void shouldAutoRunReadyLongTask() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-long-auto");
        String createPayload = "{" +
                "\"title\":\"自动推进任务\"," +
                "\"steps\":[\"step-1\",\"step-2\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String taskId = ApiTestSupport.readString(created, "$.taskId");

        mockMvc.perform(post("/api/tasks/" + userId + "/auto-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.claimedCount").value(1))
                .andExpect(jsonPath("$.advancedCount").value(1));

        mockMvc.perform(get("/api/tasks/" + userId + "/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedSteps.length()").value(1))
                .andExpect(jsonPath("$.progressPercent", greaterThan(0)));
    }

    @Test
    void shouldCreateGoalLinkedTaskAndReflectGoalProgress() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-long-goal");
        String goalPayload = "{" +
                "\"title\":\"升级 Hermes\"," +
                "\"objective\":\"完成 goal persistence\"," +
                "\"successCriteria\":\"Goal context 全链路生效\"" +
                "}";

        MvcResult createdGoal = mockMvc.perform(post("/api/goals/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalId").isString())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String goalId = ApiTestSupport.readString(createdGoal, "$.goalId");

        String taskPayload = "{" +
                "\"title\":\"接入 active goal context\"," +
                "\"steps\":[\"完成 wiring\"]," +
                "\"goalId\":\"" + goalId + "\"" +
                "}";

        MvcResult createdTask = mockMvc.perform(post("/api/tasks/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.goalId").value(goalId))
                .andReturn();
        String taskId = ApiTestSupport.readString(createdTask, "$.taskId");

        mockMvc.perform(post("/api/tasks/" + userId + "/claim")
                        .param("workerId", "worker-goal")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(taskId));

        String progressPayload = "{" +
                "\"workerId\":\"worker-goal\"," +
                "\"completedStep\":\"完成 wiring\"," +
                "\"note\":\"goal context done\"" +
                "}";

        mockMvc.perform(post("/api/tasks/" + userId + "/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.goalId").value(goalId));

        mockMvc.perform(get("/api/goals/" + userId + "/" + goalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedTaskIds.length()").value(1))
                .andExpect(jsonPath("$.linkedTaskIds[0]").value(taskId))
                .andExpect(jsonPath("$.status").value("ACHIEVED"))
                .andExpect(jsonPath("$.progressPercent").value(100));
    }

    @Test
    void shouldSplitLongTaskIntoChildTasks() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-long-split");
        String createPayload = "{" +
                "\"title\":\"推进 stage5\"," +
                "\"steps\":[\"补 graph 关系\",\"补 goal 持久化\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = ApiTestSupport.readString(created, "$.taskId");

        String splitPayload = "{" +
                "\"workerId\":\"worker-split\"," +
                "\"note\":\"split into child tasks\"" +
                "}";

        mockMvc.perform(post("/api/tasks/" + userId + "/" + taskId + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentTask.taskId").value(taskId))
                .andExpect(jsonPath("$.parentTask.childTaskIds.length()").value(2))
                .andExpect(jsonPath("$.childTasks.length()").value(2))
                .andExpect(jsonPath("$.childTasks[0].parentTaskId").value(taskId));

        mockMvc.perform(get("/api/tasks/" + userId + "/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childTaskIds.length()").value(2));
    }
}
