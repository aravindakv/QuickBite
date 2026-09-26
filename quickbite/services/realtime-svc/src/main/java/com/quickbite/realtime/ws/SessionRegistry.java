package com.quickbite.realtime.ws;

import com.quickbite.common.health.LoopWatchdog;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private final Map<String, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();
    private final LoopWatchdog watchdog;

    public SessionRegistry(LoopWatchdog watchdog, MeterRegistry meters) {
        this.watchdog = watchdog;
        meters.gauge("realtime.connections", byUser, m -> m.values().stream().mapToInt(Set::size).sum());
    }

    public void add(String userId, WebSocketSession raw) {
        // Thread-safe sends + slow-consumer protection: 5 s send limit, 64 KB buffer, then close.
        var safe = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 64 * 1024,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(safe);
    }

    public void remove(String userId, WebSocketSession raw) {
        var set = byUser.get(userId);
        if (set != null) { set.removeIf(s -> s.getId().equals(raw.getId())); if (set.isEmpty()) byUser.remove(userId); }
    }

    public void send(String userId, String json) {
        var set = byUser.get(userId);
        if (set == null) return;                 // not connected to THIS instance
        for (var s : set) {
            try { if (s.isOpen()) s.sendMessage(new TextMessage(json)); }
            catch (Exception e) { log.debug("dropping slow/broken session {}: {}", s.getId(), e.toString()); }
        }
    }

    /** App-level heartbeat keeps mobile NAT/proxy mappings alive and proves this loop still runs. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        watchdog.beat("ws-heartbeat");
        byUser.keySet().forEach(u -> send(u, "{\"type\":\"PING\"}"));
    }
}