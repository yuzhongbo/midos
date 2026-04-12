package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.dispatcher.DispatcherFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ReadinessController {

    private final DispatcherFacade dispatcherService;
    private final InflightRequestTracker tracker;

    public ReadinessController(DispatcherFacade dispatcherService, InflightRequestTracker tracker) {
        this.dispatcherService = dispatcherService;
        this.tracker = tracker;
    }

    @GetMapping("/health/readiness")
    public ResponseEntity<?> readiness() {
        boolean ready = dispatcherService.isAcceptingRequests()
                && tracker.getCount() == 0
                && dispatcherService.getActiveDispatchCount() == 0;
        return ResponseEntity.ok(Map.of(
                "ready", ready,
                "inflight", tracker.getCount(),
                "activeDispatches", dispatcherService.getActiveDispatchCount(),
                "acceptingRequests", dispatcherService.isAcceptingRequests()));
    }
}
