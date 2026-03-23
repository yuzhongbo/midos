package com.zhongbo.mindos.assistant.api;

import com.jayway.jsonpath.JsonPath;
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
        String createPayload = "{" +
                "\"title\":\"发布新版本\"," +
                "\"objective\":\"三天内完成发布\"," +
                "\"steps\":[\"准备 changelog\",\"灰度发布\",\"全量发布\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/u-long-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingSteps.length()").value(3))
                .andReturn();

        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.taskId");

        mockMvc.perform(post("/api/tasks/u-long-1/claim")
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

        mockMvc.perform(post("/api/tasks/u-long-1/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedSteps.length()").value(1))
                .andExpect(jsonPath("$.pendingSteps.length()").value(2))
                .andExpect(jsonPath("$.progressPercent", greaterThan(0)));
    }

    @Test
    void shouldRejectProgressFromDifferentWorkerLeaseOwner() throws Exception {
        String createPayload = "{" +
                "\"title\":\"跨天任务\"," +
                "\"steps\":[\"step-1\",\"step-2\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/u-long-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.taskId");

        mockMvc.perform(post("/api/tasks/u-long-2/claim")
                        .param("workerId", "worker-a")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value(taskId));

        String invalidUpdatePayload = "{" +
                "\"workerId\":\"worker-b\"," +
                "\"completedStep\":\"step-1\"" +
                "}";

        mockMvc.perform(post("/api/tasks/u-long-2/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidUpdatePayload))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/tasks/u-long-2/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedSteps.length()").value(0));
    }

    @Test
    void shouldAutoRunReadyLongTask() throws Exception {
        String createPayload = "{" +
                "\"title\":\"自动推进任务\"," +
                "\"steps\":[\"step-1\",\"step-2\"]" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/tasks/u-long-auto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.taskId");

        mockMvc.perform(post("/api/tasks/u-long-auto/auto-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-long-auto"))
                .andExpect(jsonPath("$.claimedCount").value(1))
                .andExpect(jsonPath("$.advancedCount").value(1));

        mockMvc.perform(get("/api/tasks/u-long-auto/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedSteps.length()").value(1))
                .andExpect(jsonPath("$.progressPercent", greaterThan(0)));
    }
}

