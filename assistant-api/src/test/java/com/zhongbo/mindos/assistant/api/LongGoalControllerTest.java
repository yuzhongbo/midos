package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.api.testsupport.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LongGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateListAndUpdateLongGoalStatus() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-goal-1");
        String createPayload = "{" +
                "\"title\":\"升级 Hermes\"," +
                "\"objective\":\"完成 goal persistence\"," +
                "\"successCriteria\":\"Goal context 全链路生效\"" +
                "}";

        MvcResult created = mockMvc.perform(post("/api/goals/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalId").isString())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String goalId = ApiTestSupport.readString(created, "$.goalId");

        String updatePayload = "{" +
                "\"status\":\"ON_HOLD\"," +
                "\"note\":\"等待外部依赖\"" +
                "}";

        mockMvc.perform(post("/api/goals/" + userId + "/" + goalId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.recentNotes.length()").value(1));

        mockMvc.perform(get("/api/goals/" + userId).param("status", "ON_HOLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].goalId").value(goalId))
                .andExpect(jsonPath("$[0].status").value("ON_HOLD"));
    }

    @Test
    void shouldRejectInvalidGoalCreateRequest() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("u-goal-bad");

        mockMvc.perform(post("/api/goals/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
