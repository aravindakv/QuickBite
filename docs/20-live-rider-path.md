# 20 — Live Rider Path on the Map (Track API + Polyline)

**Goal:** after payment, the customer sees the rider's **route so far** drawn on the map, moving toward their address, with distance and ETA — not just a jumping marker.

## Why a REST endpoint when WebSockets already push positions

They answer different questions:

| Channel | Gives you | Fails at |
|---|---|---|
| `RIDER_LOCATION` over WebSocket (file 08) | The **next** position, ~every 4 s | Anything that happened before you connected |
| `GET /api/orders/{id}/track` (this file) | The **path so far**, plus distance and ETA | Real-time updates |

Open the app 10 minutes into a delivery, or reconnect after a tunnel, and the WebSocket alone leaves you with an empty map until the next ping. The track endpoint **backfills**, then the socket **appends**. This backfill-then-stream pattern is how every live-tracking UI works.

```
 app opens Tracking ──GET /api/orders/{id}/track──► path so far  (draw polyline)
 then, continuously  ──WS RIDER_LOCATION──────────► append point (extend polyline)
```

## Where the data lives, and who may read it

- **location-svc** owns positions, so it records the trail in Redis.
- **order-svc** owns *who may see an order*, so the public endpoint lives there and calls location-svc internally (service token + circuit breaker, exactly like the dispatcher).

That split keeps the ownership check in one place and avoids teaching location-svc about customers.

---

## Step 1: location-svc records the trail

Add to `application.yml`:

```yaml
quickbite:
  track:
    max-points: 120        # ~8 minutes at one stored point every 4 s
    ttl: 3h
    min-metres: 25         # ignore jitter while the rider waits at the restaurant
```

`app/RiderLocationService.java` — add the script, the fields and the recording call:

```java
    public record TrackPoint(double lat, double lon, long ts) {}

    /** One round trip: append, cap the length, refresh the TTL. Called on every GPS update of a busy rider. */
    private static final DefaultRedisScript<Long> APPEND_TRACK = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return redis.call('LLEN', KEYS[1])
            """, Long.class);
```

In `update(...)`, after the GEO write:

```java
    public void update(String riderId, double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new IllegalArgumentException("bad coordinates");
        redis.opsForGeo().add(geoKey, new Point(lon, lat), riderId);
        redis.opsForValue().set("rider:alive:" + riderId, "1", aliveTtl);

        String orderId = redis.opsForValue().get("rider:busy:" + riderId);   // only riders on a delivery leave a trail
        if (orderId != null) appendTrack(orderId, lat, lon);

        var rec = new ProducerRecord<>(TOPIC, riderId, json.writeValueAsString(new LocationEvent(riderId, lat, lon, Instant.now().toEpochMilli())));
        rec.headers().add("eventType", "rider.location".getBytes(StandardCharsets.UTF_8));
        kafka.send(rec).whenComplete((r, ex) -> { if (ex != null) log.warn("location event dropped: {}", ex.toString()); });
    }

    private void appendTrack(String orderId, double lat, double lon) {
        try {
            var last = lastPoint(orderId);
            // Skip near-duplicates: a rider waiting at the restaurant would otherwise fill the list with jitter
            if (last != null && distanceKm(last.lat(), last.lon(), lat, lon) * 1000 < minMetres) return;
            String payload = json.writeValueAsString(new TrackPoint(lat, lon, Instant.now().toEpochMilli()));
            redis.execute(APPEND_TRACK, List.of(trackKey(orderId)), payload,
                    String.valueOf(maxPoints), String.valueOf(trackTtl.toSeconds()));
        } catch (Exception e) {
            log.warn("track append failed for {}: {}", orderId, e.toString());   // tracking is best-effort
        }
    }

    public List<TrackPoint> track(String orderId) {
        var raw = redis.opsForList().range(trackKey(orderId), 0, -1);
        return raw == null ? List.of() : raw.stream().map(s -> json.readValue(s, TrackPoint.class)).toList();
    }

    private TrackPoint lastPoint(String orderId) {
        var raw = redis.opsForList().index(trackKey(orderId), -1);
        return raw == null ? null : json.readValue(raw, TrackPoint.class);
    }

    private static String trackKey(String orderId) { return "track:order:" + orderId; }

    /** Equirectangular approximation: fine for a few km, far cheaper than haversine. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dx = (lon2 - lon1) * 111.32 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat2 - lat1) * 110.57;
        return Math.hypot(dx, dy);
    }
```

Add the three constructor parameters **and their fields**, or you get *"maxPoints cannot be resolved"*:

```java
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
        // ...existing assignments...
        this.maxPoints = maxPoints; this.trackTtl = trackTtl; this.minMetres = minMetres;
    }

    /** Called when a delivery ends (see the listener change below). */
    public void clearTrack(String orderId) {
        try { redis.delete(trackKey(orderId)); } catch (Exception ignored) { }
    }
```

Expose it to other services only, in its own controller:

`api/InternalTrackController.java`

```java
package com.quickbite.location.api;

import com.quickbite.location.app.RiderLocationService;
import com.quickbite.location.app.RiderLocationService.TrackPoint;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/tracks")
@PreAuthorize("hasRole('service')")
public class InternalTrackController {
    private final RiderLocationService service;
    public InternalTrackController(RiderLocationService service) { this.service = service; }

    @GetMapping("/{orderId}")
    public List<TrackPoint> track(@PathVariable String orderId) { return service.track(orderId); }
}
```

Release the trail when the delivery ends, in `messaging/OrderEventsListener.java`:

```java
        if (e.riderId() != null && ("DELIVERED".equals(e.status()) || "CANCELLED".equals(e.status()))) {
            service.release(e.riderId(), e.orderId());
            service.clearTrack(e.orderId());       // add: redis.delete(trackKey(orderId))
        }
```

## Step 2: order-svc exposes it, with the ownership check

`client/Clients.java` — extend `LocationClient`:

```java
    public record TrackPoint(double lat, double lon, long ts) {}

    @HttpExchange("/internal")
    public interface LocationClient {
        // ...existing /riders methods, with their paths updated to "/riders/..."
        @GetExchange("/tracks/{orderId}")
        List<TrackPoint> track(@PathVariable String orderId);
    }
```

> Keep the existing methods working: if your `@HttpExchange` is `"/internal/riders"`, either add a second client interface for tracks or move the prefix to `"/internal"` and prefix each rider method with `/riders`.

`api/TrackResponse.java`

```java
package com.quickbite.order.api;

import com.quickbite.order.client.Clients.TrackPoint;
import java.util.List;

public record TrackResponse(String orderId, String status, String riderId,
                            List<TrackPoint> path, TrackPoint rider, TrackPoint destination,
                            Double distanceKm, Integer etaMinutes) {}
```

`app/OrderService.java` — **add `LocationClient location` to the constructor and fields** (until now only `Dispatcher` injected it, which is why `location cannot be resolved`):

```java
    private static final double AVERAGE_SPEED_KMH = 18;   // city two-wheeler average, including stops

    public TrackResponse track(Order o) {
        String orderId = o.getId().toString();
        var destination = new TrackPoint(o.getDeliveryLat(), o.getDeliveryLon(), 0);
        if (!o.getStatus().isActiveDelivery()) {           // not out for delivery: nothing to draw
            return new TrackResponse(orderId, o.getStatus().name(), o.getRiderId(), List.of(), null, destination, null, null);
        }
        // Explicit type witness <List<TrackPoint>>: without it javac infers T from the fallback and reports
        // "run(Supplier<T>, Function<Throwable,T>) is not applicable".
        List<TrackPoint> path = breakers.create("location").<List<TrackPoint>>run(
                () -> location.track(orderId),
                t -> List.of());                           // tracking is best-effort: never fail the screen
        TrackPoint rider = path.isEmpty() ? null : path.getLast();
        Double km = rider == null ? null
                : round(distanceKm(rider.lat(), rider.lon(), destination.lat(), destination.lon()));
        Integer eta = km == null ? null : (int) Math.max(1, Math.ceil(km / AVERAGE_SPEED_KMH * 60));
        return new TrackResponse(orderId, o.getStatus().name(), o.getRiderId(), path, rider, destination, km, eta);
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dx = (lon2 - lon1) * 111.32 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat2 - lat1) * 110.57;
        return Math.hypot(dx, dy);
    }
```

`api/OrderController.java`

```java
    @GetMapping("/{id}/track")
    public TrackResponse track(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        Order o = repo.findById(id).orElseThrow(() -> new NoSuchElementException("order " + id));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_admin"));
        if (!o.visibleTo(jwt.getSubject(), admin)) throw new AccessDeniedException("not your order");
        return service.track(o);
    }
```

**ETA honesty:** straight-line distance ÷ 18 km/h is deliberately crude. A real ETA uses road routing (OSRM, Google Directions) plus historical speed per cell, which is exactly what the Spark job in file 13 would feed. Ship the crude one, label it, and replace it when you have data.

## Step 3: Gateway route

In `services/gateway/src/main/resources/application.yml`, add `/track` to the read route (file 04, step 5.7):

```yaml
            - id: orders-read
              uri: ${ORDER_URL:http://localhost:8082}
              predicates:
                - "Path=/api/orders,/api/orders/rider/active,/api/orders/{id:[0-9]{1,20}},/api/orders/{id:[0-9]{1,20}}/track"
                - Method=GET
              filters: [ValidateQuery=no-query]
```

## Step 4: Android — fetch, append, draw

`net/Models.kt`

```kotlin
@Serializable data class TrackPoint(val lat: Double, val lon: Double, val ts: Long = 0)

@Serializable data class TrackDto(
    val orderId: String, val status: String, val riderId: String? = null,
    val path: List<TrackPoint> = emptyList(), val rider: TrackPoint? = null,
    val destination: TrackPoint, val distanceKm: Double? = null, val etaMinutes: Int? = null)
```

`net/Api.kt`

```kotlin
    @GET("api/orders/{id}/track") suspend fun track(@Path("id") id: String): TrackDto
```

`ui/AppViewModel.kt` — new state, backfill, and append:

```kotlin
// in UiState:
    val riderPath: List<Pair<Double, Double>> = emptyList(),
    val etaMinutes: Int? = null,
    val distanceKm: Double? = null,
```

```kotlin
    /** Backfill the path drawn so far: called when the tracking screen opens and on every status change. */
    fun loadTrack(orderId: String) = viewModelScope.launch {
        val t = runCatching { api.track(orderId) }.getOrNull() ?: return@launch
        _state.update { st ->
            if (st.order?.id != orderId) st else st.copy(
                riderPath = t.path.map { it.lat to it.lon },
                riderPos = t.rider?.let { it.lat to it.lon } ?: st.riderPos,
                etaMinutes = t.etaMinutes, distanceKm = t.distanceKm)
        }
    }
```

`loadTrack` needs **three** call sites; miss them and the polyline only ever appears after the next socket message.

**1. Right after placing the order** (`placeOrder`), so the screen starts consistent:

```kotlin
        val order = api.placeOrder(checkoutKey, req)
        _state.update { it.copy(order = order, payment = null, riderPos = null, riderPath = emptyList(),
            etaMinutes = null, distanceKm = null,
            log = listOf("Order ${order.id}: ${order.status}"), screen = Screen.Tracking) }
        loadTrack(order.id)                     // empty at first; refreshed on every status change below
```

**2. On every status change** (`ORDER_STATUS` branch of `startUpdates`), which is when a rider is assigned and the trail starts existing:

```kotlin
                    "ORDER_STATUS" -> {
                        val status = msg["status"]!!.jsonPrimitive.content
                        val orderId = msg["orderId"]!!.jsonPrimitive.content
                        _state.update { st ->
                            st.copy(order = st.order?.takeIf { it.id == orderId }?.copy(status = status) ?: st.order,
                                    log = (st.log + "Order $orderId → $status").takeLast(30))
                        }
                        if (_state.value.order?.id == orderId) {
                            loadTrack(orderId)                    // <-- backfill the path for the new status
                            if (status in setOf("PAID", "CANCELLED", "DELIVERED")) {
                                runCatching { api.payment(orderId) }.getOrNull()?.let { p -> _state.update { it.copy(payment = p) } }
                            }
                        }
                    }
```

**3. Whenever the Tracking screen appears**, which covers a cold start, a process death, or coming back from another screen. In `ui/Screens.kt`:

```kotlin
@Composable private fun Tracking(s: UiState, vm: AppViewModel) {
    val o = s.order ?: return
    LaunchedEffect(o.id) { vm.loadTrack(o.id) }      // backfill on (re)entry; needs androidx.compose.runtime.LaunchedEffect
    Text("Order ${o.id}", style = MaterialTheme.typography.titleMedium)
    // ...rest unchanged
```

In the `RIDER_LOCATION` branch, append instead of replacing:

```kotlin
                    "RIDER_LOCATION" -> {
                        val p = msg["lat"]!!.jsonPrimitive.double to msg["lon"]!!.jsonPrimitive.double
                        _state.update {
                            it.copy(riderPos = p,
                                    riderPath = (it.riderPath + p).takeLast(300))   // bound the polyline
                        }
                    }
```

`ui/MapPane.kt` — draw the polyline and frame everything:

```kotlin
package com.quickbite.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapPane(
    destination: Pair<Double, Double>,
    rider: Pair<Double, Double>?,
    path: List<Pair<Double, Double>> = emptyList(),
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(280.dp),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            val dest = GeoPoint(destination.first, destination.second)

            if (path.size >= 2) {                                   // the route travelled so far
                map.overlays.add(Polyline(map).apply {
                    setPoints(path.map { GeoPoint(it.first, it.second) })
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.color = 0xFF1E88E5.toInt()
                })
            }
            rider?.let { r ->                                       // dashed "as the crow flies" leg to the door
                map.overlays.add(Polyline(map).apply {
                    setPoints(listOf(GeoPoint(r.first, r.second), dest))
                    outlinePaint.strokeWidth = 5f
                    outlinePaint.color = 0x661E88E5
                })
                map.overlays.add(Marker(map).apply { position = GeoPoint(r.first, r.second); title = "Rider" })
            }
            map.overlays.add(Marker(map).apply { position = dest; title = "You" })

            // Frame rider + path + destination instead of centring on one point
            val all = (path.map { GeoPoint(it.first, it.second) } + listOfNotNull(rider?.let { GeoPoint(it.first, it.second) }) + dest)
            map.post {
                if (all.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.4f), true, 60)
                else map.controller.setCenter(dest)
            }
            map.invalidate()
        })
}
```

`ui/Screens.kt` — pass the path and show the ETA in `Tracking`:

```kotlin
    MapPane(destination = o.deliveryLat to o.deliveryLon, rider = s.riderPos, path = s.riderPath)
    s.etaMinutes?.let { eta ->
        Text("Arriving in ~$eta min" + (s.distanceKm?.let { " · %.1f km away".format(it) } ?: ""),
             style = MaterialTheme.typography.titleMedium)
    }
```

## Step 5: Offline demo (file 16)

`DemoState` gains `val tracks: Map<String, List<TrackPoint>> = emptyMap()`. In `DemoFulfillment.location(...)`, record the point:

```kotlin
    private suspend fun location(orderId: String, p: Pair<Double, Double>) {
        store.update { s ->
            val pts = (s.tracks[orderId] ?: emptyList()) + TrackPoint(p.first, p.second, System.currentTimeMillis())
            s.copy(tracks = s.tracks + (orderId to pts.takeLast(120)))
        }
        _events.emit(/* ...unchanged RIDER_LOCATION json... */)
    }
```

and `DemoServer` answers the endpoint from it:

```kotlin
        m == "GET" && p.size == 4 && p[1] == "orders" && p[3] == "track" -> {
            val o = store.state.orders.find { it.id == p[2] } ?: throw ApiError(404, "order ${p[2]} not found")
            val pts = store.state.tracks[o.id] ?: emptyList()
            val dest = TrackPoint(o.deliveryLat, o.deliveryLon)
            val rider = pts.lastOrNull()
            val km = rider?.let { CardCheck.distanceKm(it.lat, it.lon, dest.lat, dest.lon) }
            ok(TrackDto(o.id, o.status, o.riderId, pts, rider, dest,
                        km?.let { Math.round(it * 100) / 100.0 },
                        km?.let { maxOf(1, Math.ceil(it / 18 * 60).toInt()) }))
        }
```

(Put `distanceKm` in a small `Geo` object rather than `CardCheck`; it's the same formula as the server's.)

## Step 6: Verify

**Server:**

```bash
scripts/rider-sim.sh & sleep 5
ALICE=$(scripts/token.sh alice alice)
O=$(curl -sS -X POST localhost:8000/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | jq -r .id)

# wait for RIDER_ASSIGNED, then watch the path grow
for i in $(seq 1 10); do
  curl -sS -H "Authorization: Bearer $(scripts/token.sh alice alice)" localhost:8000/api/orders/$O/track \
    | jq -c '{status, points: (.path | length), distanceKm, etaMinutes}'
  sleep 4
done

docker exec quickbite-redis-1 redis-cli llen "track:order:$O"     # grows, capped at max-points
kill %1
```

| Check | Pass condition |
|---|---|
| `points` | Increases over time; stops growing at `max-points` |
| `distanceKm` / `etaMinutes` | Both shrink as the rider approaches |
| Before assignment | `status: PAID`, empty `path`, null ETA |
| Another user's order | `403` (the ownership check in order-svc) |
| location-svc stopped | The endpoint still answers with an empty path (breaker fallback), and the screen still renders |
| After delivery | `track:order:<id>` key is gone |

**On the phone (test M21):** place an order with a rider online. The map should show a blue line growing behind the rider, a faint straight line to your address, both markers in frame, and "Arriving in ~N min" counting down. Kill and reopen the app mid-delivery: the path must be **restored immediately** (that's the backfill), then keep extending.

## Step 7: What this costs (update file 18)

Tracking is not free, and the numbers are worth doing before shipping:

- **Redis ops:** one extra scripted call per stored GPS point. The `min-metres` filter and the 1/s socket throttle mean roughly one store per 4 s per busy rider, so about 270k → ~490k ops/s at peak in the file 18 model.
- **Memory:** 120 points × ~50 B ≈ 6 KB per active order. At 1.95M orders in flight that is **~12 GB**, which roughly *doubles* the cache tier (11 shards → ~17, so 22 → ~34 cache servers).

Cheaper options, in order of preference:

1. **Store fewer points:** 40 points at 15 s spacing still draws a smooth line (~2 KB/order, ~4 GB).
2. **Shorter horizon:** keep only the last 5 minutes; older path is rarely looked at.
3. **Reconstruct on demand** from the Kafka topic or the Parquet lake (file 13) for the rare "show me the whole route" case, and keep only the recent tail hot.

Re-run `scripts/capacity_model.py` with the point count you choose, rather than assuming the current cache sizing still holds.
