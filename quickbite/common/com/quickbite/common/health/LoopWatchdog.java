package com.quickbite.common.health;

import org.springframework.boot.health.contributor.Health;          // Boot 3.x: org.springframework.boot.actuate.health
import org.springframework.boot.health.contributor.HealthIndicator;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LoopWatchdog implements HealthIndicator {
    private final Map<String, AtomicLong> beats = new ConcurrentHashMap<>();
    private final long timeoutNanos;

    public LoopWatchdog(Duration timeout) { this.timeoutNanos = timeout.toNanos(); }

    public void beat(String loop) {
        beats.computeIfAbsent(loop, k -> new AtomicLong()).set(System.nanoTime());
    }

    @SuppressWarnings("null")
    @Override
    public Health health() {
        long now = System.nanoTime();
        var stuck = beats.entrySet().stream()
                .filter(e -> now - e.getValue().get() > timeoutNanos)
                .map(Map.Entry::getKey).toList();
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (stuck.isEmpty() && deadlocked == null) {
            return Health.up().withDetail("loops", beats.keySet()).build();
        }
        return Health.down()
                .withDetail("stuckLoops", stuck)
                .withDetail("deadlockedThreads", deadlocked == null ? 0 : deadlocked.length)
                .build();
    }
}