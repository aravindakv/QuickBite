# 08 — location-svc and realtime-svc: WebSockets, Redis GEO, Distributed Locks, Fan-out

**Goal:** two services that make the app feel "live":

- **location-svc** ingests rider GPS over WebSocket (or REST), stores it in a Redis geo index, finds the nearest free riders for the dispatcher, and hands out **exclusive rider claims**.
- **realtime-svc** keeps customer and rider WebSocket connections open and pushes order status and live rider position to the right phone.

---

## Concepts first

### Why two services instead of one?

They scale on **different resources**:

| | location-svc | realtime-svc |
|---|---|---|
| Workload | Write-heavy: 75k GPS updates/s at scale | Connection-heavy: 1M idle-ish sockets at scale |
| Bottleneck | CPU and Redis ops | Memory per connection, file descriptors |
| Scale on | Messages/s | Open connections |

Merged, you would have to over-provision one resource to get the other.

### Redis GEO in one paragraph

`GEOADD riders:blr lon lat bob` stores bob in a **sorted set** whose score is a 52-bit **geohash** of the position. Nearby points share geohash prefixes, so they sit next to each other in the sorted order.

`GEOSEARCH ... BYRADIUS 5 km` only has to scan the 9 geohash cells around the point: **O(log N + M)** instead of computing the distance to all N riders. This is the "significance of the best algorithm" in practice. At 300k riders, a linear scan costs about 300k distance computations per dispatch; the geo index costs about 20.

### Distributed lock done right: claim and release

- **Claim:** `SET rider:busy:bob <orderId> NX EX 10800`. `NX` means only if the key is absent, so it is atomic, and only one claimant ever wins. `EX` sets an expiry, so a crashed owner can't hold the rider forever.
- **Release:** delete **only if the value is still my orderId**. Otherwise a late release for an old order could free a rider who is busy with a *new* order. Check-and-delete must be atomic, so it runs as a **Lua script inside Redis**.

### Fan-out and the "which pod holds the socket?" problem

Alice's socket lives on *one* realtime-svc instance. How does an event reach that instance?

- **Here (simple, good to ~10 instances):** every realtime-svc instance consumes **all** events, using a unique consumer group per instance (broadcast). Each instance pushes only to the sockets it holds locally.
- **At scale:** keep a registry `ws:user:{id} → podId` in Redis and route each message only to the owning pod (via per-pod Redis channels or per-pod Kafka partitions). Broadcasting 75k msgs/s to 36 pods would waste 35/36 of the work. Write this trade-off up as an ADR.

### Slow consumers

A phone in a tunnel stops reading, so its server-side send buffer grows. With 100k such phones, the server runs out of memory. `ConcurrentWebSocketSessionDecorator` caps the buffer (64 KB) and the send time (5 s) and **closes** the slow session. The client reconnects later. This is **backpressure**: protect the many from the few.

---

# Part A: location-svc

`services/location-svc/build.gradle.kts`

```kotlin
plugins { id("quickbite.spring-service") }

dependencies {
    implementation(project(":libs:common"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")   // Redis via GenericContainer (file 11)
}
```

`src/main/resources/application.yml`

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
  application:
    name: location-svc
  data:
    redis:
      host: ${REDIS_HOST:localhost}
  kafka:
    consumer:
      group-id: location-svc

server:
  port: 8084

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,redis

quickbite:
  city: blr
  rider:
    alive-ttl: 30s        # rider must report at least every 30 s to be dispatchable
    claim-ttl: 3h
```

All Java lives under `services/location-svc/src/main/java/com/quickbite/location/`.

`LocationApplication.java`

```java
package com.quickbite.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LocationApplication {
    public static void main(String[] args) { SpringApplication.run(LocationApplication.class, args); }
}
```

`app/RiderLocationService.java`

```java
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
```

`api/RiderController.java`: the public API, for riders (via the gateway).

```java
package com.quickbite.location.api;

import com.quickbite.location.app.RiderLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/riders")
@PreAuthorize("hasRole('rider')")
public class RiderController {
    public record LocationUpdate(double lat, double lon) {}
    private final RiderLocationService service;

    public RiderController(RiderLocationService service) { this.service = service; }

    /** REST alternative to the WebSocket: handy for scripts and load tests. */
    @PostMapping("/me/location")
    public ResponseEntity<Void> update(@AuthenticationPrincipal Jwt jwt, @RequestBody LocationUpdate u) {
        service.update(jwt.getSubject(), u.lat(), u.lon());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        var m = new HashMap<String, Object>();
        m.put("riderId", jwt.getSubject());
        m.put("currentOrder", service.currentClaim(jwt.getSubject()));
        return m;
    }
}
```

`api/InternalRiderController.java`: only reachable inside the network, and only with the `service` role.

```java
package com.quickbite.location.api;

import com.quickbite.location.app.RiderLocationService;
import com.quickbite.location.app.RiderLocationService.Candidate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/riders")
@PreAuthorize("hasRole('service')")
public class InternalRiderController {
    private final RiderLocationService service;
    public InternalRiderController(RiderLocationService service) { this.service = service; }

    @GetMapping("/nearest")
    public List<Candidate> nearest(@RequestParam double lat, @RequestParam double lon,
                                   @RequestParam(defaultValue = "5") double radiusKm, @RequestParam(defaultValue = "5") int limit) {
        return service.nearest(lat, lon, radiusKm, Math.min(limit, 20));
    }

    /** 200 {claimed:false} instead of 409: "rider taken" is a normal outcome, not an error. */
    @PostMapping("/{riderId}/claim")
    public Map<String, Boolean> claim(@PathVariable String riderId, @RequestParam String orderId) {
        return Map.of("claimed", service.claim(riderId, orderId));
    }

    @PostMapping("/{riderId}/release")
    public Map<String, Boolean> release(@PathVariable String riderId, @RequestParam String orderId) {
        return Map.of("released", service.release(riderId, orderId));
    }
}
```

> **Why `{claimed:false}` and not HTTP 409?** A 409 is a 4xx *exception* in the HTTP client, and the circuit breaker would count it as a *failure*. Ten busy riders in a row would open the breaker on a perfectly healthy service. Business outcomes are not infrastructure failures.

`ws/RiderSocketHandler.java`

```java
package com.quickbite.location.ws;

import com.quickbite.location.app.RiderLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RiderSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RiderSocketHandler.class);
    record Msg(double lat, double lon) {}

    private final RiderLocationService service;
    private final JsonMapper json;
    private final Map<String, Long> lastUpdate = new ConcurrentHashMap<>();

    public RiderSocketHandler(RiderLocationService service, JsonMapper json) { this.service = service; this.json = json; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // The HTTP upgrade request already passed Spring Security (JWT), so the principal is set.
        if (!(session.getPrincipal() instanceof Authentication auth)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_rider"))) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("riders only"));
            return;
        }
        log.info("rider {} connected", auth.getName());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String riderId = session.getPrincipal().getName();
        long now = System.currentTimeMillis();
        Long prev = lastUpdate.put(riderId, now);
        if (prev != null && now - prev < 1000) return;   // server-side throttle: max 1 update/s per rider
        Msg m = json.readValue(message.getPayload(), Msg.class);
        service.update(riderId, m.lat(), m.lon());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() != null) lastUpdate.remove(session.getPrincipal().getName());
    }
}
```

`ws/WebSocketConfig.java`

```java
package com.quickbite.location.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RiderSocketHandler handler;
    public WebSocketConfig(RiderSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/rider").setAllowedOriginPatterns("*"); // native apps send no Origin; tighten for browsers
    }
}
```

`messaging/OrderEventsListener.java`: frees riders when deliveries end.

```java
package com.quickbite.location.messaging;

import com.quickbite.location.app.RiderLocationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration.header;

@Component
public class OrderEventsListener {
    record StatusChanged(String orderId, String customerId, String status, String riderId) {}
    private final RiderLocationService service;
    private final JsonMapper json;

    public OrderEventsListener(RiderLocationService service, JsonMapper json) { this.service = service; this.json = json; }

    @KafkaListener(topics = "orders.events")
    public void on(ConsumerRecord<String, String> rec) {
        if (!"order.status-changed".equals(header(rec, "eventType"))) return;
        StatusChanged e = json.readValue(rec.value(), StatusChanged.class);
        if (e.riderId() != null && ("DELIVERED".equals(e.status()) || "CANCELLED".equals(e.status()))) {
            service.release(e.riderId(), e.orderId());   // idempotent by nature: second release returns 0
        }
    }
}
```

> Unknown JSON fields (e.g. `deliveryLat`) are ignored by default in Jackson 3, so each consumer declares only the fields it needs. This is the **tolerant reader** pattern, and it lets producers add fields without breaking consumers.

`messaging/TopicConfig.java`

```java
package com.quickbite.location.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class TopicConfig {
    @Bean
    KafkaAdmin.NewTopics locationTopics(@Value("${KAFKA_PARTITIONS:3}") int p, @Value("${KAFKA_REPLICAS:1}") short r) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("rider.location").partitions(p).replicas(r)
                        .config("retention.ms", String.valueOf(24 * 3600 * 1000L)).build(),   // 24 h: it's a firehose
                TopicBuilder.name("orders.events").partitions(p).replicas(r).build(),
                TopicBuilder.name("orders.events.dlt").partitions(p).replicas(r).build());
    }
}
```

---

# Part B: realtime-svc

`services/realtime-svc/build.gradle.kts`

```kotlin
plugins { id("quickbite.spring-service") }

dependencies {
    implementation(project(":libs:common"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

`src/main/resources/application.yml`

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
  application:
    name: realtime-svc
  data:
    redis:
      host: ${REDIS_HOST:localhost}

server:
  port: 8085

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,redis

quickbite:
  realtime:
    # A UNIQUE consumer group per instance = every instance receives every event (broadcast fan-out)
    group: realtime-${random.uuid}
```

All Java lives under `services/realtime-svc/src/main/java/com/quickbite/realtime/`.

`RealtimeApplication.java`

```java
package com.quickbite.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RealtimeApplication {
    public static void main(String[] args) { SpringApplication.run(RealtimeApplication.class, args); }
}
```

`ws/SessionRegistry.java`

```java
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
```

`ws/UpdatesSocketHandler.java`

```java
package com.quickbite.realtime.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class UpdatesSocketHandler extends TextWebSocketHandler {
    private final SessionRegistry registry;
    public UpdatesSocketHandler(SessionRegistry registry) { this.registry = registry; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.add(session.getPrincipal().getName(), session);   // principal = JWT "sub"
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() != null) registry.remove(session.getPrincipal().getName(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Clients may send {"type":"PONG"}; nothing else is accepted on this channel.
    }
}
```

`ws/WebSocketConfig.java`

```java
package com.quickbite.realtime.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final UpdatesSocketHandler handler;
    public WebSocketConfig(UpdatesSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/updates").setAllowedOriginPatterns("*");
    }
}
```

`messaging/FanOutListener.java`

```java
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
```

> **Optimization to try later:** `onLocation` performs a Redis GET per GPS update per instance. At scale, cache `track:rider:*` in a local Caffeine cache for ~5 s, filled by `onOrder`. Measure the drop in Redis ops/s in file 12.

---

## Rider simulator script (for testing with one phone)

`scripts/rider-sim.sh`: plays "bob" so you can test the full flow with just one device as alice.

```bash
#!/usr/bin/env bash
# usage: scripts/rider-sim.sh [bob] [bob]
set -euo pipefail
U=${1:-bob}; P=${2:-bob}; API=http://localhost:8000
LAT=12.9345; LON=77.6230          # start near restaurant r1
tok() { scripts/token.sh "$U" "$P"; }
T=$(tok); started=$(date +%s)

post_loc() { curl -s -o /dev/null -X POST "$API/api/riders/me/location" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d "{\"lat\":$1,\"lon\":$2}"; }

echo "Rider $U online at $LAT,$LON: waiting for an assignment..."
while true; do
  (( $(date +%s) - started > 240 )) && { T=$(tok); started=$(date +%s); }   # refresh the 5-min token
  post_loc $LAT $LON
  ORDER=$(curl -s "$API/api/orders/rider/active" -H "Authorization: Bearer $T")
  if [ -n "$ORDER" ]; then
    ID=$(echo "$ORDER" | jq -r .id); DLAT=$(echo "$ORDER" | jq -r .deliveryLat); DLON=$(echo "$ORDER" | jq -r .deliveryLon)
    echo "Assigned order $ID -> picking up"
    sleep 3; curl -s -o /dev/null -X POST "$API/api/orders/$ID/pickup" -H "Authorization: Bearer $T"
    for i in $(seq 1 20); do                                  # 20 steps x 2 s = 40 s ride
      CLAT=$(awk -v a=$LAT -v b=$DLAT -v i=$i 'BEGIN{printf "%.6f", a+(b-a)*i/20}')
      CLON=$(awk -v a=$LON -v b=$DLON -v i=$i 'BEGIN{printf "%.6f", a+(b-a)*i/20}')
      post_loc $CLAT $CLON; echo "  step $i/20 at $CLAT,$CLON"; sleep 2
    done
    curl -s -X POST "$API/api/orders/$ID/deliver" -H "Authorization: Bearer $T" | jq -r .status
    LAT=12.9345; LON=77.6230                                  # ride back to the start
  fi
  sleep 4
done
```

```bash
chmod +x scripts/rider-sim.sh
```

The script uses REST; the Android rider mode (file 10) uses the WebSocket. Both hit the same service method.

---

## Run and verify the complete backend flow

Start all six services (gateway, catalog, order, payment, location, realtime) with `bootRun` in separate terminals, or wait for file 09 to run them in Docker.

Install `websocat` (`brew install websocat`) to act as a WebSocket client.

```bash
# Terminal A: alice listens for live updates
ALICE=$(scripts/token.sh alice alice)
websocat -H "Authorization: Bearer $ALICE" ws://localhost:8000/ws/updates

# Terminal B: bob comes online
scripts/rider-sim.sh

# Terminal C: alice orders
curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | jq -r .id
```

**In terminal A you should see, in order:**

```
{"type":"ORDER_STATUS","status":"PAID",...}
{"type":"ORDER_STATUS","status":"RIDER_ASSIGNED","riderId":"<bob-sub>",...}
{"type":"ORDER_STATUS","status":"PICKED_UP",...}
{"type":"RIDER_LOCATION","lat":12.93..,"lon":77.62..}      <- every 2 s, 20 times
{"type":"ORDER_STATUS","status":"DELIVERED",...}
{"type":"PING"}                                            <- every 25 s
```

**More checks:**

```bash
# Geo index and claims in Redis
docker exec -it quickbite-redis-1 redis-cli GEOSEARCH riders:blr FROMLONLAT 77.6271 12.9279 BYRADIUS 5 km ASC WITHDIST
docker exec -it quickbite-redis-1 redis-cli --scan --pattern 'rider:busy:*'   # empty after delivery (released)

# Payment captured after delivery
curl -s -H "Authorization: Bearer $ALICE" localhost:8000/api/payments/orders/<ORDER_ID> | jq .status   # CAPTURED

# Security: a customer cannot open the rider ingest socket
websocat -H "Authorization: Bearer $ALICE" ws://localhost:8000/ws/rider     # closed: "riders only"

# Internal APIs are not reachable from outside
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $ALICE" "localhost:8000/internal/riders/nearest?lat=1&lon=1"  # 403 at gateway

# Two riders, one order: start a second simulator as carol; only ONE of them gets each order
scripts/rider-sim.sh carol carol
```

| Check | Pass condition |
|---|---|
| Live stream | All status events, then about 20 `RIDER_LOCATION` messages |
| Claims | No `rider:busy:*` keys after delivery |
| Payment | `CAPTURED` |
| Security | Rider socket closed for alice; internal API returns `403` |
| Two riders | Each order is assigned to exactly one rider |

**Checkpoint:**

1. The rider's alive key has a 30 s TTL. What happens to dispatch if a rider's phone dies mid-shift, and why is that the right behaviour?
2. Explain why the `release` Lua script must compare the value before deleting. Construct the exact race it prevents.
