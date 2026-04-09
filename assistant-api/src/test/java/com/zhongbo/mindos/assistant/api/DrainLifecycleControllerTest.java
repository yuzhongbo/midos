package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.security.risky-ops.admin-token-header=X-MindOS-Admin-Token",
        "mindos.security.risky-ops.admin-token=test-admin-token"
})
@AutoConfigureMockMvc
class DrainLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DispatcherService dispatcherService;

    @MockBean
    private MemoryManager memoryManager;

    @Autowired
    private InflightRequestTracker tracker;

    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final AtomicLong activeDispatches = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        acceptingRequests.set(true);
        activeDispatches.set(0L);
        while (tracker.getCount() > 0) {
            tracker.decrement();
        }

        when(dispatcherService.isAcceptingRequests()).thenAnswer(invocation -> acceptingRequests.get());
        when(dispatcherService.getActiveDispatchCount()).thenAnswer(invocation -> activeDispatches.get());
        doAnswer(invocation -> {
            acceptingRequests.set(false);
            return null;
        }).when(dispatcherService).beginDrain();
        when(dispatcherService.waitForActiveDispatches(anyLong())).thenReturn(true);
        doNothing().when(memoryManager).persistPending();
    }

    @Test
    void shouldReportReadyAndDrainLifecycle() throws Exception {
        mockMvc.perform(get("/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.inflight").value(0))
                .andExpect(jsonPath("$.activeDispatches").value(0))
                .andExpect(jsonPath("$.acceptingRequests").value(true));

        mockMvc.perform(post("/admin/drain?timeoutMs=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drained").value(true))
                .andExpect(jsonPath("$.persisted").value(true))
                .andExpect(jsonPath("$.inflight").value(0))
                .andExpect(jsonPath("$.activeDispatches").value(0));

        verify(dispatcherService).beginDrain();
        verify(dispatcherService).waitForActiveDispatches(anyLong());
        verify(memoryManager).persistPending();

        mockMvc.perform(get("/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.acceptingRequests").value(false));
    }

    @Test
    void shouldExposeNotReadyWhenInFlightDispatchExists() throws Exception {
        acceptingRequests.set(false);
        activeDispatches.set(1L);
        tracker.increment();

        mockMvc.perform(get("/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.inflight").value(1))
                .andExpect(jsonPath("$.activeDispatches").value(1))
                .andExpect(jsonPath("$.acceptingRequests").value(false));
    }
}

