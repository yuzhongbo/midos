package com.zhongbo.mindos.assistant.api.news;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class NewsPushScheduler {

    private static final Logger LOGGER = Logger.getLogger(NewsPushScheduler.class.getName());
    private final NewsPushService newsPushService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "news-push-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

    public NewsPushScheduler(NewsPushService newsPushService) {
        this.newsPushService = newsPushService;
    }

    @PostConstruct
    public void start() {
        refresh();
    }

    public synchronized void refresh() {
        ScheduledFuture<?> existing = futureRef.getAndSet(null);
        if (existing != null) {
            existing.cancel(false);
        }
        if (!newsPushService.getConfig().enabled()) {
            return;
        }
        Optional<ZonedDateTime> nextOpt = newsPushService.nextRunTime();
        if (nextOpt.isEmpty()) {
            return;
        }
        ZonedDateTime next = nextOpt.get();
        long delayMs = Math.max(0L, Duration.between(ZonedDateTime.now(next.getZone()), next).toMillis());
        ScheduledFuture<?> scheduled = scheduler.schedule(this::executeAndReschedule, delayMs, TimeUnit.MILLISECONDS);
        futureRef.set(scheduled);
        LOGGER.info("NewsPushScheduler: next run at " + next);
    }

    @PreDestroy
    public void shutdown() {
        ScheduledFuture<?> existing = futureRef.getAndSet(null);
        if (existing != null) {
            existing.cancel(false);
        }
        scheduler.shutdownNow();
    }

    private void executeAndReschedule() {
        try {
            newsPushService.pushOnce();
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "NewsPushScheduler: push failed", ex);
        } finally {
            refresh();
        }
    }
}
