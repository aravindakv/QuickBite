package com.quickbite.realtime.messaging;

import com.quickbite.realtime.ws.SessionRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration.header;

@Component
public class FanOutListener {
    record StatusChanged(String orderId, String customerId, String status, String riderId, double deliveryLat, double deliveryLon) {}
    record Location(String riderId, double lat, double lon, long ts) {}

    private final SessionRegistry sessions;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public FanOutListener(SessionRegistry sessions, StringRedisTemplate redis, JsonMapper json) {
        this.sessions = sessions; this.redis = redis; this.json = json;
    }

    // "latest": a fresh instance only needs NEW events; old positions are useless for a live map.
    @KafkaListener(topics = "orders.events", groupId = "${quickbite.realtime.group}",
                   properties = "auto.offset.reset=latest")
    public void onOrder(ConsumerRecord<String, String> rec) {
        if (!"order.status-changed".equals(header(rec, "eventType"))) return;
        StatusChanged e = json.readValue(rec.value(), StatusChanged.class);
        String msg = json.writeValueAsString(Map.of("type", "ORDER_STATUS", "orderId", e.orderId(),
                "status", e.status(), "riderId", e.riderId() == null ? "" : e.riderId()));
        sessions.send(e.customerId(), msg);
        if (e.riderId() != null) sessions.send(e.riderId(), msg);   // the rider app learns about assignments instantly

        String trackKey = "track:rider:" + e.riderId();
        switch (e.status()) {
            case "RIDER_ASSIGNED", "PICKED_UP" -> redis.opsForValue().set(trackKey, e.customerId() + "|" + e.orderId(), Duration.ofHours(3));
            case "DELIVERED", "CANCELLED" -> { if (e.riderId() != null) redis.delete(trackKey); }
            default -> {}
        }
    }

    @KafkaListener(topics = "rider.location", groupId = "${quickbite.realtime.group}",
                   properties = "auto.offset.reset=latest")
    public void onLocation(ConsumerRecord<String, String> rec) {
        Location l = json.readValue(rec.value(), Location.class);
        String track = redis.opsForValue().get("track:rider:" + l.riderId());
        if (track == null) return;                       // rider isn't on a delivery: nobody to tell
        String[] parts = track.split("\\|");
        sessions.send(parts[0], json.writeValueAsString(Map.of("type", "RIDER_LOCATION", "orderId", parts[1],
                "lat", l.lat(), "lon", l.lon(), "ts", l.ts())));
    }
}