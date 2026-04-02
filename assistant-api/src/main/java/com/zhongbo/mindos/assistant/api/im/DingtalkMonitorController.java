package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.api.AdminTokenGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/im/dingtalk")
public class DingtalkMonitorController {

    private final DingtalkOpenApiConversationSender conversationSender;
    private final DingtalkOpenApiMessageClient openApiMessageClient;
    private final AdminTokenGuard adminTokenGuard;
    private final boolean tokenMonitorEnabled;

    public DingtalkMonitorController(DingtalkOpenApiConversationSender conversationSender,
                                     DingtalkOpenApiMessageClient openApiMessageClient,
                                     AdminTokenGuard adminTokenGuard,
                                     @Value("${mindos.im.dingtalk.token-monitor.enabled:true}") boolean tokenMonitorEnabled) {
        this.conversationSender = conversationSender;
        this.openApiMessageClient = openApiMessageClient;
        this.adminTokenGuard = adminTokenGuard;
        this.tokenMonitorEnabled = tokenMonitorEnabled;
    }

    @GetMapping("/token-monitor")
    public Map<String, Object> tokenMonitor(HttpServletRequest request) {
        adminTokenGuard.verify(request, "admin", "im.dingtalk.token-monitor", "/api/im/dingtalk/token-monitor");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", tokenMonitorEnabled);
        response.put("timestamp", Instant.now().toString());
        if (!tokenMonitorEnabled) {
            response.put("note", "token monitor disabled by config");
            return Map.copyOf(response);
        }
        response.put("streamOutbound", conversationSender.tokenMonitorSnapshot());
        response.put("openApiFallback", openApiMessageClient.tokenMonitorSnapshot());
        return Map.copyOf(response);
    }

    @GetMapping("/outbound-debug")
    public Map<String, Object> outboundDebug(HttpServletRequest request) {
        adminTokenGuard.verify(request, "admin", "im.dingtalk.outbound-debug", "/api/im/dingtalk/outbound-debug");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now().toString());
        response.put("streamOutbound", conversationSender.outboundDebugSnapshot());
        return Map.copyOf(response);
    }
}

