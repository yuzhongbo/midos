package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.memory.MemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.DispatcherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.logging.Logger;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger LOGGER = Logger.getLogger(AdminController.class.getName());

    private final InflightRequestTracker tracker;
    private final MemoryFacade memoryFacade;
    private final DispatcherService dispatcherService;

    public AdminController(InflightRequestTracker tracker, MemoryFacade memoryFacade, DispatcherService dispatcherService) {
        this.tracker = tracker;
        this.memoryFacade = memoryFacade;
        this.dispatcherService = dispatcherService;
    }

    @PostMapping("/drain")
    public ResponseEntity<?> drain(@RequestParam(name = "timeoutMs", defaultValue = "30000") long timeoutMs) {
        LOGGER.info("Admin drain requested, timeoutMs=" + timeoutMs);
        dispatcherService.beginDrain();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        boolean quiesced = tracker.waitForZero(Math.max(0L, deadline - System.currentTimeMillis()));
        boolean dispatcherQuiesced = dispatcherService.waitForActiveDispatches(Math.max(0L, deadline - System.currentTimeMillis()));
        try {
            memoryFacade.persistPending();
        } catch (Exception ex) {
            LOGGER.warning("persistPending failed during drain: " + ex.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "drained", quiesced && dispatcherQuiesced,
                    "persisted", false,
                    "activeDispatches", dispatcherService.getActiveDispatchCount(),
                    "inflight", tracker.getCount(),
                    "error", ex.getMessage()));
        }
        return ResponseEntity.ok(Map.of(
                "drained", quiesced && dispatcherQuiesced,
                "persisted", true,
                "activeDispatches", dispatcherService.getActiveDispatchCount(),
                "inflight", tracker.getCount()));
    }

    @GetMapping("/ready")
    public ResponseEntity<?> readiness() {
        boolean ready = dispatcherService.isAcceptingRequests() && tracker.getCount() == 0 && dispatcherService.getActiveDispatchCount() == 0;
        return ResponseEntity.ok(Map.of(
                "ready", ready,
                "inflight", tracker.getCount(),
                "activeDispatches", dispatcherService.getActiveDispatchCount(),
                "acceptingRequests", dispatcherService.isAcceptingRequests()));
    }
}
