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
    public record TrackPoint(double lat, double lon, long ts) {}

    /** Atomic compare-and-delete: release only if the claim still belongs to this order. */
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end
            """, Long.class);

    /** One round trip: append the point, cap the list, refresh the TTL. */
    private static final DefaultRedisScript<Long> APPEND_TRACK = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return redis.call('LLEN', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String geoKey;
    private final Duration aliveTtl;
    private final Duration claimTtl;
    private final int maxPoints;
    private final Duration trackTtl;
    private final double minMetres;

    public RiderLocationService(StringRedisTemplate redis, KafkaTemplate<String, String> kafka, JsonMapper json,
                                @Value("${quickbite.city}") String city,
                                @Value("${quickbite.rider.alive-ttl}") Duration aliveTtl,
                                @Value("${quickbite.rider.claim-ttl}") Duration claimTtl,
                                @Value("${quickbite.track.max-points:120}") int maxPoints,
                                @Value("${quickbite.track.ttl:3h}") Duration trackTtl,
                                @Value("${quickbite.track.min-metres:25}") double minMetres) {
        this.redis = redis; this.kafka = kafka; this.json = json;
        this.geoKey = "riders:" + city;
        this.aliveTtl = aliveTtl; this.claimTtl = claimTtl;
        this.maxPoints = maxPoints; this.trackTtl = trackTtl; this.minMetres = minMetres;
    }

    public void update(String riderId, double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new IllegalArgumentException("bad coordinates");
        redis.opsForGeo().add(geoKey, new Point(lon, lat), riderId);          // note: (lon, lat) order!
        redis.opsForValue().set("rider:alive:" + riderId, "1", aliveTtl);

        String orderId = redis.opsForValue().get("rider:busy:" + riderId);    // only riders on a delivery leave a trail
        if (orderId != null) appendTrack(orderId, lat, lon);

        // Location is AP data: fire-and-forget (no outbox). Losing one point out of a 4 s stream is harmless.
        var rec = new ProducerRecord<>(TOPIC, riderId,
                json.writeValueAsString(new LocationEvent(riderId, lat, lon, Instant.now().toEpochMilli())));
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

    // ---------------- rider trail (file 20) ----------------

    /** Best-effort: a failed trail write must never break a GPS update. */
    private void appendTrack(String orderId, double lat, double lon) {
        try {
            TrackPoint last = lastPoint(orderId);
            // Skip near-duplicates: a rider waiting at the restaurant would otherwise fill the list with jitter.
            if (last != null && distanceKm(last.lat(), last.lon(), lat, lon) * 1000 < minMetres) return;
            String payload = json.writeValueAsString(new TrackPoint(lat, lon, Instant.now().toEpochMilli()));
            redis.execute(APPEND_TRACK, List.of(trackKey(orderId)),
                    payload, String.valueOf(maxPoints), String.valueOf(trackTtl.toSeconds()));
        } catch (Exception e) {
            log.warn("track append failed for order {}: {}", orderId, e.toString());
        }
    }

    public List<TrackPoint> track(String orderId) {
        try {
            var raw = redis.opsForList().range(trackKey(orderId), 0, -1);
            return raw == null ? List.of() : raw.stream().map(s -> json.readValue(s, TrackPoint.class)).toList();
        } catch (Exception e) {
            log.warn("track read failed for order {}: {}", orderId, e.toString());
            return List.of();
        }
    }

    /** Called when the delivery ends, so finished orders don't hold memory for the full TTL. */
    public void clearTrack(String orderId) {
        try { redis.delete(trackKey(orderId)); } catch (Exception ignored) { }
    }

    private TrackPoint lastPoint(String orderId) {
        String raw = redis.opsForList().index(trackKey(orderId), -1);
        return raw == null ? null : json.readValue(raw, TrackPoint.class);
    }

    private static String trackKey(String orderId) { return "track:order:" + orderId; }

    /** Equirectangular approximation: accurate enough over a few km, far cheaper than haversine. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dx = (lon2 - lon1) * 111.32 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat2 - lat1) * 110.57;
        return Math.hypot(dx, dy);
    }
}
