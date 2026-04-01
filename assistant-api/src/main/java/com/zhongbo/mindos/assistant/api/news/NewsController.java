package com.zhongbo.mindos.assistant.api.news;

import com.zhongbo.mindos.assistant.api.AdminTokenGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final boolean requireAdminToken;
    private final AdminTokenGuard adminTokenGuard;
    private final NewsPushService newsPushService;
    private final NewsPushScheduler newsPushScheduler;

    public NewsController(@Value("${mindos.news.require-admin-token:true}") boolean requireAdminToken,
                          AdminTokenGuard adminTokenGuard,
                          NewsPushService newsPushService,
                          NewsPushScheduler newsPushScheduler) {
        this.requireAdminToken = requireAdminToken;
        this.adminTokenGuard = adminTokenGuard;
        this.newsPushService = newsPushService;
        this.newsPushScheduler = newsPushScheduler;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        verify(request, "news-status");
        NewsPushConfig cfg = newsPushService.getConfig();
        Optional<ZonedDateTime> next = newsPushService.nextRunTime();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", cfg.enabled());
        resp.put("sources", cfg.sources());
        resp.put("maxItems", cfg.maxItems());
        resp.put("cron", cfg.cron());
        resp.put("timezone", cfg.timezone());
        resp.put("sessionWebhook", cfg.sessionWebhook());
        resp.put("openConversationId", cfg.openConversationId());
        resp.put("senderId", cfg.senderId());
        resp.put("messageMaxChars", cfg.messageMaxChars());
        resp.put("nextRunTime", next.map(ZonedDateTime::toString).orElse(""));
        return resp;
    }

    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody NewsConfigRequest requestBody,
                                            HttpServletRequest request) {
        verify(request, "news-config");
        NewsPushConfig existing = newsPushService.getConfig();
        NewsPushConfig candidate = merge(existing, requestBody);
        try {
            candidate.cronExpression(); // validate
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cron expression");
        }
        NewsPushConfig updated = newsPushService.updateConfig(candidate);
        newsPushScheduler.refresh();
        Optional<ZonedDateTime> next = newsPushService.nextRunTime();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("enabled", updated.enabled());
        resp.put("sources", updated.sources());
        resp.put("maxItems", updated.maxItems());
        resp.put("cron", updated.cron());
        resp.put("timezone", updated.timezone());
        resp.put("nextRunTime", next.map(ZonedDateTime::toString).orElse(""));
        return resp;
    }

    @PostMapping("/push")
    public Map<String, Object> pushNow(HttpServletRequest request) {
        verify(request, "news-push");
        NewsPushResult result = newsPushService.pushOnce();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", result.delivered() ? "delivered" : "not_delivered");
        resp.put("fetched", result.fetchedCount());
        resp.put("used", result.usedCount());
        resp.put("channel", result.channel());
        resp.put("error", result.error());
        resp.put("timestamp", result.timestamp().toString());
        resp.put("summary", result.summary());
        return resp;
    }

    private NewsPushConfig merge(NewsPushConfig existing, NewsConfigRequest request) {
        boolean enabled = request != null && request.enabled() != null ? request.enabled() : existing.enabled();
        return new NewsPushConfig(
                enabled,
                request != null && request.sources() != null ? request.sources() : existing.sources(),
                request != null && request.maxItems() != null ? request.maxItems() : existing.maxItems(),
                request != null && request.cron() != null ? request.cron() : existing.cron(),
                request != null && request.timezone() != null ? request.timezone() : existing.timezone(),
                request != null && request.sessionWebhook() != null ? request.sessionWebhook() : existing.sessionWebhook(),
                request != null && request.openConversationId() != null ? request.openConversationId() : existing.openConversationId(),
                request != null && request.senderId() != null ? request.senderId() : existing.senderId(),
                request != null && request.messageMaxChars() != null ? request.messageMaxChars() : existing.messageMaxChars()
        );
    }

    private void verify(HttpServletRequest request, String action) {
        if (!requireAdminToken) {
            return;
        }
        adminTokenGuard.verify(request, "news", "news." + action, action);
    }
}
