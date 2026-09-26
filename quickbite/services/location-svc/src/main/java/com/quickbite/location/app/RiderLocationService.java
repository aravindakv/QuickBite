package com.quickbite.location.app;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class RiderLocationService {
    private static final Logger log = LoggerFactory.getLogger(RiderLocationService.class);
    public static final String TOPIC = "rider.location";

    public record LocationEvent(String riderId, double lat, double lon, long ts) {}
    public record Candidate(String riderId, double distanceKm) {}

    /** Atomic compare-and-delete: release only if the claim still belongs to this order. */
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String geoKey;
    private final Duration aliveTtl;
    private final Duration claimTtl;

    public RiderLocationService(StringRedisTemplate redis, KafkaTemplate<String, String> kafka, JsonMapper json,
                                @Value("${quickbite.city}") String city,
                                @Value("${quickbite.rider.alive-ttl}") Duration aliveTtl,
                                @Value("${quickbite.rider.claim-ttl}") Duration claimTtl) {
        this.redis = redis; this.kafka = kafka; this.json = json;
        this.geoKey = "riders:" + city; this.aliveTtl = aliveTtl; this.claimTtl = claimTtl;
    }

    public void update(String riderId, double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new IllegalArgumentException("bad coordinates");
        redis.opsForGeo().add(geoKey, new Point(lon, lat), riderId);          // note: (lon, lat) order!
        redis.opsForValue().set("rider:alive:" + riderId, "1", aliveTtl);

        // Location is AP data: fire-and-forget (no outbox). Losing one point out of a stream every 4 s is harmless,
        // and blocking the socket thread on Kafka acks would add latency to 75k msgs/s.
        var rec = new ProducerRecord<>(TOPIC, riderId, json.writeValueAsString(new LocationEvent(riderId, lat, lon, Instant.now().toEpochMilli())));
        rec.headers().add("eventType", "rider.location".getBytes(StandardCharsets.UTF_8));
        kafka.send(rec).whenComplete((r, ex) -> { if (ex != null) log.warn("location event dropped: {}", ex.toString()); });
    }

    public List<Candidate> nearest(double lat, double lon, double radiusKm, int limit) {
        var args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance().sortAscending().limit(limit * 4L);          // over-fetch, then filter busy/offline
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(geoKey,
                GeoReference.fromCoordinate(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS), args);
        List<Candidate> out = new ArrayList<>();
        if (results == null) return out;
        for (var r : results) {
            String id = r.getContent().getName();
            boolean alive = Boolean.TRUE.equals(redis.hasKey("rider:alive:" + id));
            boolean busy = Boolean.TRUE.equals(redis.hasKey("rider:busy:" + id));
            if (alive && !busy) out.add(new Candidate(id, r.getDistance().getValue()));
            if (out.size() == limit) break;
        }
        return out;
    }

    public boolean claim(String riderId, String orderId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("rider:busy:" + riderId, orderId, claimTtl));
    }

    public boolean release(String riderId, String orderId) {
        Long n = redis.execute(RELEASE, List.of("rider:busy:" + riderId), orderId);
        return n != null && n == 1;
    }

    public String currentClaim(String riderId) { return redis.opsForValue().get("rider:busy:" + riderId); }
}