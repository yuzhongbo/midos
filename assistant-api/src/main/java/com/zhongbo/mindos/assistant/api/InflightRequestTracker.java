package com.zhongbo.mindos.assistant.api;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InflightRequestTracker {
    private final AtomicInteger counter = new AtomicInteger(0);

    public void increment() {
        counter.incrementAndGet();
    }

    public void decrement() {
        int v = counter.decrementAndGet();
        if (v < 0) {
            counter.set(0);
        }
    }

    public int getCount() {
        return counter.get();
    }

    public boolean waitForZero(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() <= 0) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return counter.get() <= 0;
    }
}

