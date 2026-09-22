# 05 — catalog-svc: MongoDB, Cache-Aside with Redis, HTTP Caching

**Goal:** a read-heavy service that serves restaurants and menus through three cache layers, and you will see each layer work:

1. **Edge cache:** NGINX, standing in for a CDN.
2. **Shared cache:** Redis, with TTL jitter and stampede protection.
3. **Database:** MongoDB with geo indexes.

---

## Concepts first

### Why MongoDB for the catalog?

A restaurant with its menu is a **document**: it is read as a whole, rarely joined, and its schema varies (some items have add-ons, some don't). Embedding the menu in the restaurant document means a single read with no joins. Mongo also has native geo indexes (`2dsphere`) for "restaurants near me". The price is weaker multi-document transactions, which the catalog doesn't need.

### Cache-aside, and the three classic cache bugs

With **cache-aside**, the application checks Redis first. On a miss it loads from the DB and fills Redis with a TTL. We defend against three failure modes:

| Bug | What happens | Our defense |
|---|---|---|
| **Stampede / thundering herd** | A hot key expires and 1,000 concurrent requests all miss and hit Mongo together | A **single-flight lock**: `SET key:lock NX EX 5`. One request rebuilds; the others wait ~25 ms and re-read. |
| **Synchronized expiry** | Keys written together all expire together, causing a periodic DB spike | **TTL jitter**: 60 s + random(0–15 s) |
| **Cache outage = total outage** | Redis down makes every request error | Redis errors are caught and we **fall back to the DB**. The cache is an optimization, never a dependency. |

### Why order-svc will NOT read prices from the cache

Browsing may show a price up to 60 s stale (an AP choice: fast, eventually consistent). Charging money must use the authoritative price, so the pricing endpoint reads Mongo directly.

---

## Step 1: Build and config

`services/catalog-svc/build.gradle.kts`

```kotlin
plugins { id("quickbite.spring-service") }

dependencies {
    implementation(project(":libs:common"))
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
}
```

`services/catalog-svc/src/main/resources/application.yml`

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
  application:
    name: catalog-svc
  mongodb:
    uri: ${MONGO_URI:mongodb://localhost:27017/catalog}   # Boot 4 key
  data:
    mongodb:
      uri: ${MONGO_URI:mongodb://localhost:27017/catalog} # Boot 3 key (the migrator tells you which one is live)
      auto-index-creation: true
    redis:
      host: ${REDIS_HOST:localhost}

server:
  port: 8081

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,mongo,redis   # not ready if its stores are unreachable

quickbite:
  security:
    public-paths:
      - "GET:/api/restaurants"
      - "GET:/api/restaurants/**"
  cache:
    ttl: 60s
```

> If startup fails with *"Included health contributor … does not exist"*, temporarily set `include: readinessState`, start the service, open `/actuator/health`, and copy the exact contributor names from the output.

---

## Step 2: Domain

All Java lives under `services/catalog-svc/src/main/java/com/quickbite/catalog/`.

`CatalogApplication.java`

```java
package com.quickbite.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CatalogApplication {
    public static void main(String[] args) { SpringApplication.run(CatalogApplication.class, args); }
}
```

`domain/Restaurant.java`

```java
package com.quickbite.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document("restaurants")
public record Restaurant(
        @Id String id,
        String name,
        String cuisine,
        @Indexed String cityId,                                           // shard key candidate (file 12 / capacity plan)
        String area,                                                      // neighbourhood, for display
        @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE) GeoJsonPoint location, // GeoJSON order: [lon, lat]
        double rating,
        int avgPrepMinutes,
        String imageUrl,
        List<MenuItem> menu) {}
```

`domain/MenuItem.java`

```java
package com.quickbite.catalog.domain;

import java.math.BigDecimal;

public record MenuItem(String id, String name, String description, BigDecimal price,
                       boolean veg, boolean available, String imageUrl) {}
```

`domain/RestaurantRepository.java`

```java
package com.quickbite.catalog.domain;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface RestaurantRepository extends MongoRepository<Restaurant, String> {
    List<Restaurant> findByCityId(String cityId);
}
```

`api/Dtos.java`

```java
package com.quickbite.catalog.api;

import com.quickbite.catalog.domain.MenuItem;
import com.quickbite.catalog.domain.Restaurant;
import java.math.BigDecimal;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record RestaurantSummary(String id, String name, String cuisine, String area, double rating,
                                    int avgPrepMinutes, String imageUrl, double lat, double lon) {
        public static RestaurantSummary from(Restaurant r) {
            return new RestaurantSummary(r.id(), r.name(), r.cuisine(), r.area(), r.rating(), r.avgPrepMinutes(),
                    r.imageUrl(), r.location().getY(), r.location().getX());
        }
    }

    public record RestaurantDetail(RestaurantSummary restaurant, List<MenuItem> menu) {
        public static RestaurantDetail from(Restaurant r) { return new RestaurantDetail(RestaurantSummary.from(r), r.menu()); }
    }

    public record MenuItemPrice(String id, String name, BigDecimal price, boolean available) {}

    public record PriceUpdate(BigDecimal price) {}
}
```

---

## Step 3: Cache-aside service

`app/CatalogService.java`

```java
package com.quickbite.catalog.app;

import com.quickbite.catalog.api.Dtos.*;
import com.quickbite.catalog.domain.Restaurant;
import com.quickbite.catalog.domain.RestaurantRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class CatalogService {
    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private final RestaurantRepository repo;
    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final Duration ttl;

    public CatalogService(RestaurantRepository repo, MongoTemplate mongo, StringRedisTemplate redis,
                          JsonMapper json, MeterRegistry meters, @Value("${quickbite.cache.ttl}") Duration ttl) {
        this.repo = repo; this.mongo = mongo; this.redis = redis; this.json = json; this.meters = meters; this.ttl = ttl;
    }

    public List<RestaurantSummary> byCity(String city) {
        RestaurantSummary[] arr = cached(cityKey(city), RestaurantSummary[].class, () ->
                repo.findByCityId(city).stream().map(RestaurantSummary::from).toArray(RestaurantSummary[]::new));
        return List.of(arr);
    }

    public RestaurantDetail detail(String id) {
        return cached(detailKey(id), RestaurantDetail.class, () ->
                repo.findById(id).map(RestaurantDetail::from)
                    .orElseThrow(() -> new NoSuchElementException("restaurant " + id + " not found")));
    }

    /** Authoritative prices for ordering: ALWAYS from the database, never from cache. */
    public List<MenuItemPrice> prices(String restaurantId, Collection<String> itemIds) {
        Restaurant r = repo.findById(restaurantId)
                .orElseThrow(() -> new NoSuchElementException("restaurant " + restaurantId + " not found"));
        Set<String> wanted = new HashSet<>(itemIds);
        return r.menu().stream().filter(m -> wanted.contains(m.id()))
                .map(m -> new MenuItemPrice(m.id(), m.name(), m.price(), m.available())).toList();
    }

    /** Write path: update the source of truth, THEN invalidate caches (never update the cache in place). */
    public void updatePrice(String restaurantId, String itemId, BigDecimal price) {
        if (price.signum() <= 0) throw new IllegalArgumentException("price must be positive");
        var q = Query.query(Criteria.where("_id").is(restaurantId).and("menu.id").is(itemId));
        var result = mongo.updateFirst(q, new Update().set("menu.$.price", price), Restaurant.class);
        if (result.getMatchedCount() == 0) throw new NoSuchElementException("item not found");
        Restaurant r = repo.findById(restaurantId).orElseThrow();
        safeDelete(detailKey(restaurantId));
        safeDelete(cityKey(r.cityId()));
        // NOTE: the NGINX edge cache still serves the old page for up to 60 s. That is the
        // CDN trade-off; real CDNs offer an explicit "purge" API for this.
    }

    // ---------- cache-aside with single-flight + jitter + fail-open ----------

    private <T> T cached(String key, Class<T> type, Supplier<T> loader) {
        String hit = safeGet(key);
        if (hit != null) { count("hit"); return json.readValue(hit, type); }

        String lockKey = key + ":lock";
        boolean leader = safeLock(lockKey);
        if (!leader) {
            // Someone else is rebuilding: wait briefly for their result instead of hammering Mongo.
            for (int i = 0; i < 20; i++) {
                sleep(25);
                hit = safeGet(key);
                if (hit != null) { count("hit_after_wait"); return json.readValue(hit, type); }
            }
            // Leader too slow or dead: load ourselves (availability over efficiency).
        }
        try {
            count("miss");
            T value = loader.get();
            safeSet(key, json.writeValueAsString(value), jitteredTtl());
            return value;
        } finally {
            if (leader) safeDelete(lockKey);
        }
    }

    private Duration jitteredTtl() { return ttl.plusSeconds(ThreadLocalRandom.current().nextLong(0, 16)); }

    private String safeGet(String key) {
        try { return redis.opsForValue().get(key); }
        catch (Exception e) { log.warn("cache read failed, falling back to DB: {}", e.toString()); count("error"); return null; }
    }
    private void safeSet(String key, String v, Duration ttl) {
        try { redis.opsForValue().set(key, v, ttl); } catch (Exception e) { log.warn("cache write failed: {}", e.toString()); }
    }
    private boolean safeLock(String key) {
        try { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", LOCK_TTL)); }
        catch (Exception e) { return true; } // no Redis -> no coordination; just load
    }
    private void safeDelete(String key) { try { redis.delete(key); } catch (Exception ignored) {} }
    private void count(String result) { meters.counter("catalog.cache", "result", result).increment(); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static String cityKey(String city) { return "cache:restaurants:city:" + city; }
    private static String detailKey(String id) { return "cache:restaurant:" + id; }
}
```

---

## Step 4: Controller with HTTP caching headers

`api/CatalogController.java`

```java
package com.quickbite.catalog.api;

import com.quickbite.catalog.api.Dtos.*;
import com.quickbite.catalog.app.CatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/restaurants")
public class CatalogController {
    private static final CacheControl PUBLIC_60S = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();
    private final CatalogService service;

    public CatalogController(CatalogService service) { this.service = service; }

    /** Cache-Control: public tells NGINX/CDN (and the phone's HTTP cache) that this may be shared for 60 s. */
    @GetMapping
    public ResponseEntity<List<RestaurantSummary>> list(@RequestParam(defaultValue = "blr") String city) {
        return ResponseEntity.ok().cacheControl(PUBLIC_60S).body(service.byCity(city));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestaurantDetail> detail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(PUBLIC_60S).body(service.detail(id));
    }

    /** Used by order-svc at checkout. Never cached. */
    @GetMapping("/{id}/menu-items")
    public ResponseEntity<List<MenuItemPrice>> prices(@PathVariable String id, @RequestParam List<String> ids) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.prices(id, ids));
    }

    @PutMapping("/{id}/menu-items/{itemId}/price")
    @PreAuthorize("hasAnyRole('restaurant','admin')")
    public ResponseEntity<Void> updatePrice(@PathVariable String id, @PathVariable String itemId, @RequestBody PriceUpdate body) {
        service.updatePrice(id, itemId, body.price());
        return ResponseEntity.noContent().build();
    }
}
```

`config/HttpCacheConfig.java`: adds an **ETag**, so a client that sends `If-None-Match` gets `304 Not Modified` with no body (it saves mobile data).

```java
package com.quickbite.catalog.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class HttpCacheConfig {
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        var reg = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        reg.addUrlPatterns("/api/restaurants", "/api/restaurants/*");
        return reg;
    }
}
```

---

## Step 5: Seed data (22 restaurants, 132 menu items, 2 cities)

Test data lives in a **JSON file**, not in Java. This lets you add restaurants without recompiling logic, testers can read and edit it, and the same file can feed other tools (k6 scripts, the Android debug menu).

**Built-in test cases in this data** (full list in `15-test-data.md`):

- **Two cities:** `blr` (16 restaurants) and `mum` (6). Use `?city=mum` to test city partitioning and the city-level cache keys.
- **Sold-out items:** the **last item of every menu** has `"available": false`. Ordering it must fail with `409 ... is sold out`.
- **Price outlier:** `r16 Premium Pizza Co` costs 1.8× (to test totals and larger payment amounts).
- **Veg / non-veg** mix in every menu. The last item of `r12` (Kathi Roll) is non-veg and sold out.
- `r4 Green Bowl` sits exactly on the default delivery point (12.9279, 77.6271), for short-distance tests.

`services/catalog-svc/src/main/resources/seed/restaurants.json`

```json
[
  {"id": "r1", "name": "Dosa Junction", "cuisine": "South Indian", "cityId": "blr", "area": "Koramangala", "lat": 12.9352, "lon": 77.6245, "rating": 4.5, "avgPrepMinutes": 15, "menu": [
    {"id": "r1-i1", "name": "Masala Dosa", "price": 120, "veg": true, "available": true},
    {"id": "r1-i2", "name": "Idli Vada Combo", "price": 90, "veg": true, "available": true},
    {"id": "r1-i3", "name": "Rava Dosa", "price": 130, "veg": true, "available": true},
    {"id": "r1-i4", "name": "Mini Meals", "price": 180, "veg": true, "available": true},
    {"id": "r1-i5", "name": "Filter Coffee", "price": 40, "veg": true, "available": true},
    {"id": "r1-i6", "name": "Ghee Pongal", "price": 110, "veg": true, "available": false}
  ]},
  {"id": "r2", "name": "Biryani Bros", "cuisine": "Hyderabadi", "cityId": "blr", "area": "BTM Layout", "lat": 12.934, "lon": 77.6101, "rating": 4.3, "avgPrepMinutes": 25, "menu": [
    {"id": "r2-i1", "name": "Chicken Dum Biryani", "price": 320, "veg": false, "available": true},
    {"id": "r2-i2", "name": "Mutton Biryani", "price": 420, "veg": false, "available": true},
    {"id": "r2-i3", "name": "Veg Dum Biryani", "price": 240, "veg": true, "available": true},
    {"id": "r2-i4", "name": "Double Ka Meetha", "price": 120, "veg": true, "available": true},
    {"id": "r2-i5", "name": "Mirchi Ka Salan", "price": 90, "veg": true, "available": true},
    {"id": "r2-i6", "name": "Haleem", "price": 350, "veg": false, "available": false}
  ]},
  {"id": "r3", "name": "Pasta Stop", "cuisine": "Italian", "cityId": "blr", "area": "Indiranagar", "lat": 12.9719, "lon": 77.6412, "rating": 4.1, "avgPrepMinutes": 20, "menu": [
    {"id": "r3-i1", "name": "Margherita Pizza", "price": 350, "veg": true, "available": true},
    {"id": "r3-i2", "name": "Penne Arrabbiata", "price": 320, "veg": true, "available": true},
    {"id": "r3-i3", "name": "Chicken Alfredo", "price": 420, "veg": false, "available": true},
    {"id": "r3-i4", "name": "Garlic Bread", "price": 150, "veg": true, "available": true},
    {"id": "r3-i5", "name": "Tiramisu", "price": 260, "veg": true, "available": true},
    {"id": "r3-i6", "name": "Truffle Risotto", "price": 520, "veg": true, "available": false}
  ]},
  {"id": "r4", "name": "Green Bowl", "cuisine": "Salads", "cityId": "blr", "area": "Koramangala", "lat": 12.9279, "lon": 77.6271, "rating": 4.6, "avgPrepMinutes": 10, "menu": [
    {"id": "r4-i1", "name": "Greek Salad", "price": 280, "veg": true, "available": true},
    {"id": "r4-i2", "name": "Quinoa Power Bowl", "price": 340, "veg": true, "available": true},
    {"id": "r4-i3", "name": "Grilled Chicken Salad", "price": 360, "veg": false, "available": true},
    {"id": "r4-i4", "name": "Cold-pressed Juice", "price": 180, "veg": true, "available": true},
    {"id": "r4-i5", "name": "Hummus & Pita", "price": 220, "veg": true, "available": true},
    {"id": "r4-i6", "name": "Acai Bowl", "price": 390, "veg": true, "available": false}
  ]},
  {"id": "r5", "name": "Tandoor Tales", "cuisine": "North Indian", "cityId": "blr", "area": "Marathahalli", "lat": 12.9591, "lon": 77.6974, "rating": 4.2, "avgPrepMinutes": 25, "menu": [
    {"id": "r5-i1", "name": "Paneer Butter Masala", "price": 290, "veg": true, "available": true},
    {"id": "r5-i2", "name": "Dal Makhani", "price": 240, "veg": true, "available": true},
    {"id": "r5-i3", "name": "Butter Chicken", "price": 360, "veg": false, "available": true},
    {"id": "r5-i4", "name": "Butter Naan", "price": 60, "veg": true, "available": true},
    {"id": "r5-i5", "name": "Jeera Rice", "price": 160, "veg": true, "available": true},
    {"id": "r5-i6", "name": "Gulab Jamun", "price": 90, "veg": true, "available": false}
  ]},
  {"id": "r6", "name": "Wok This Way", "cuisine": "Chinese", "cityId": "blr", "area": "HSR Layout", "lat": 12.9121, "lon": 77.6446, "rating": 4.0, "avgPrepMinutes": 18, "menu": [
    {"id": "r6-i1", "name": "Veg Hakka Noodles", "price": 220, "veg": true, "available": true},
    {"id": "r6-i2", "name": "Chilli Chicken", "price": 300, "veg": false, "available": true},
    {"id": "r6-i3", "name": "Veg Manchurian", "price": 240, "veg": true, "available": true},
    {"id": "r6-i4", "name": "Schezwan Fried Rice", "price": 250, "veg": true, "available": true},
    {"id": "r6-i5", "name": "Hot & Sour Soup", "price": 150, "veg": true, "available": true},
    {"id": "r6-i6", "name": "Dim Sum Basket", "price": 320, "veg": false, "available": false}
  ]},
  {"id": "r7", "name": "Burger Barn", "cuisine": "Burgers", "cityId": "blr", "area": "HSR Layout", "lat": 12.9116, "lon": 77.6389, "rating": 4.1, "avgPrepMinutes": 15, "menu": [
    {"id": "r7-i1", "name": "Classic Veg Burger", "price": 180, "veg": true, "available": true},
    {"id": "r7-i2", "name": "Crispy Chicken Burger", "price": 240, "veg": false, "available": true},
    {"id": "r7-i3", "name": "Loaded Fries", "price": 160, "veg": true, "available": true},
    {"id": "r7-i4", "name": "Chocolate Shake", "price": 190, "veg": true, "available": true},
    {"id": "r7-i5", "name": "Double Patty Burger", "price": 320, "veg": false, "available": true},
    {"id": "r7-i6", "name": "Onion Rings", "price": 140, "veg": true, "available": false}
  ]},
  {"id": "r8", "name": "Sugar Rush", "cuisine": "Desserts", "cityId": "blr", "area": "Indiranagar", "lat": 12.9784, "lon": 77.6408, "rating": 4.7, "avgPrepMinutes": 10, "menu": [
    {"id": "r8-i1", "name": "Death by Chocolate", "price": 280, "veg": true, "available": true},
    {"id": "r8-i2", "name": "Red Velvet Pastry", "price": 160, "veg": true, "available": true},
    {"id": "r8-i3", "name": "Belgian Waffle", "price": 220, "veg": true, "available": true},
    {"id": "r8-i4", "name": "Kulfi Falooda", "price": 180, "veg": true, "available": true},
    {"id": "r8-i5", "name": "Cheesecake Slice", "price": 260, "veg": true, "available": true},
    {"id": "r8-i6", "name": "Brownie Sundae", "price": 240, "veg": true, "available": false}
  ]},
  {"id": "r9", "name": "Malabar Kitchen", "cuisine": "Kerala", "cityId": "blr", "area": "Bellandur", "lat": 12.9304, "lon": 77.6784, "rating": 4.4, "avgPrepMinutes": 22, "menu": [
    {"id": "r9-i1", "name": "Appam & Stew", "price": 220, "veg": true, "available": true},
    {"id": "r9-i2", "name": "Kerala Parotta", "price": 50, "veg": true, "available": true},
    {"id": "r9-i3", "name": "Fish Curry Meals", "price": 320, "veg": false, "available": true},
    {"id": "r9-i4", "name": "Beef Fry", "price": 300, "veg": false, "available": true},
    {"id": "r9-i5", "name": "Avial", "price": 180, "veg": true, "available": true},
    {"id": "r9-i6", "name": "Payasam", "price": 110, "veg": true, "available": false}
  ]},
  {"id": "r10", "name": "Nawab's Table", "cuisine": "Mughlai", "cityId": "blr", "area": "MG Road", "lat": 12.9756, "lon": 77.605, "rating": 4.3, "avgPrepMinutes": 30, "menu": [
    {"id": "r10-i1", "name": "Galouti Kebab", "price": 380, "veg": false, "available": true},
    {"id": "r10-i2", "name": "Nihari", "price": 420, "veg": false, "available": true},
    {"id": "r10-i3", "name": "Shahi Paneer", "price": 300, "veg": true, "available": true},
    {"id": "r10-i4", "name": "Sheermal", "price": 70, "veg": true, "available": true},
    {"id": "r10-i5", "name": "Mutton Korma", "price": 440, "veg": false, "available": true},
    {"id": "r10-i6", "name": "Phirni", "price": 120, "veg": true, "available": false}
  ]},
  {"id": "r11", "name": "Tokyo Box", "cuisine": "Japanese", "cityId": "blr", "area": "Whitefield", "lat": 12.9698, "lon": 77.75, "rating": 4.2, "avgPrepMinutes": 25, "menu": [
    {"id": "r11-i1", "name": "Veg Sushi Platter", "price": 480, "veg": true, "available": true},
    {"id": "r11-i2", "name": "Salmon Nigiri", "price": 620, "veg": false, "available": true},
    {"id": "r11-i3", "name": "Chicken Ramen", "price": 450, "veg": false, "available": true},
    {"id": "r11-i4", "name": "Edamame", "price": 220, "veg": true, "available": true},
    {"id": "r11-i5", "name": "Miso Soup", "price": 180, "veg": true, "available": true},
    {"id": "r11-i6", "name": "Matcha Ice Cream", "price": 240, "veg": true, "available": false}
  ]},
  {"id": "r12", "name": "Chaat Corner", "cuisine": "Street Food", "cityId": "blr", "area": "Jayanagar", "lat": 12.925, "lon": 77.5938, "rating": 4.5, "avgPrepMinutes": 8, "menu": [
    {"id": "r12-i1", "name": "Pani Puri", "price": 60, "veg": true, "available": true},
    {"id": "r12-i2", "name": "Pav Bhaji", "price": 140, "veg": true, "available": true},
    {"id": "r12-i3", "name": "Vada Pav", "price": 40, "veg": true, "available": true},
    {"id": "r12-i4", "name": "Sev Puri", "price": 80, "veg": true, "available": true},
    {"id": "r12-i5", "name": "Masala Chai", "price": 30, "veg": true, "available": true},
    {"id": "r12-i6", "name": "Kathi Roll", "price": 150, "veg": false, "available": false}
  ]},
  {"id": "r13", "name": "Udupi Upahara", "cuisine": "South Indian", "cityId": "blr", "area": "Malleshwaram", "lat": 13.0031, "lon": 77.5643, "rating": 4.6, "avgPrepMinutes": 12, "menu": [
    {"id": "r13-i1", "name": "Masala Dosa", "price": 120, "veg": true, "available": true},
    {"id": "r13-i2", "name": "Idli Vada Combo", "price": 90, "veg": true, "available": true},
    {"id": "r13-i3", "name": "Rava Dosa", "price": 130, "veg": true, "available": true},
    {"id": "r13-i4", "name": "Mini Meals", "price": 180, "veg": true, "available": true},
    {"id": "r13-i5", "name": "Filter Coffee", "price": 40, "veg": true, "available": true},
    {"id": "r13-i6", "name": "Ghee Pongal", "price": 110, "veg": true, "available": false}
  ]},
  {"id": "r14", "name": "Mangalore Pearl", "cuisine": "Coastal", "cityId": "blr", "area": "JP Nagar", "lat": 12.9063, "lon": 77.5857, "rating": 4.4, "avgPrepMinutes": 25, "menu": [
    {"id": "r14-i1", "name": "Prawn Gassi", "price": 450, "veg": false, "available": true},
    {"id": "r14-i2", "name": "Neer Dosa", "price": 90, "veg": true, "available": true},
    {"id": "r14-i3", "name": "Fish Thali", "price": 380, "veg": false, "available": true},
    {"id": "r14-i4", "name": "Kori Rotti", "price": 360, "veg": false, "available": true},
    {"id": "r14-i5", "name": "Sol Kadhi", "price": 80, "veg": true, "available": true},
    {"id": "r14-i6", "name": "Mangalore Buns", "price": 70, "veg": true, "available": false}
  ]},
  {"id": "r15", "name": "Late Night Wok", "cuisine": "Chinese", "cityId": "blr", "area": "Electronic City", "lat": 12.8452, "lon": 77.6602, "rating": 3.8, "avgPrepMinutes": 20, "menu": [
    {"id": "r15-i1", "name": "Veg Hakka Noodles", "price": 220, "veg": true, "available": true},
    {"id": "r15-i2", "name": "Chilli Chicken", "price": 300, "veg": false, "available": true},
    {"id": "r15-i3", "name": "Veg Manchurian", "price": 240, "veg": true, "available": true},
    {"id": "r15-i4", "name": "Schezwan Fried Rice", "price": 250, "veg": true, "available": true},
    {"id": "r15-i5", "name": "Hot & Sour Soup", "price": 150, "veg": true, "available": true},
    {"id": "r15-i6", "name": "Dim Sum Basket", "price": 320, "veg": false, "available": false}
  ]},
  {"id": "r16", "name": "Premium Pizza Co", "cuisine": "Italian", "cityId": "blr", "area": "Koramangala", "lat": 12.9345, "lon": 77.623, "rating": 4.8, "avgPrepMinutes": 28, "menu": [
    {"id": "r16-i1", "name": "Margherita Pizza", "price": 630, "veg": true, "available": true},
    {"id": "r16-i2", "name": "Penne Arrabbiata", "price": 576, "veg": true, "available": true},
    {"id": "r16-i3", "name": "Chicken Alfredo", "price": 756, "veg": false, "available": true},
    {"id": "r16-i4", "name": "Garlic Bread", "price": 270, "veg": true, "available": true},
    {"id": "r16-i5", "name": "Tiramisu", "price": 468, "veg": true, "available": true},
    {"id": "r16-i6", "name": "Truffle Risotto", "price": 936, "veg": true, "available": false}
  ]},
  {"id": "r17", "name": "Bandra Bites", "cuisine": "Burgers", "cityId": "mum", "area": "Bandra", "lat": 19.0596, "lon": 72.8295, "rating": 4.3, "avgPrepMinutes": 15, "menu": [
    {"id": "r17-i1", "name": "Classic Veg Burger", "price": 180, "veg": true, "available": true},
    {"id": "r17-i2", "name": "Crispy Chicken Burger", "price": 240, "veg": false, "available": true},
    {"id": "r17-i3", "name": "Loaded Fries", "price": 160, "veg": true, "available": true},
    {"id": "r17-i4", "name": "Chocolate Shake", "price": 190, "veg": true, "available": true},
    {"id": "r17-i5", "name": "Double Patty Burger", "price": 320, "veg": false, "available": true},
    {"id": "r17-i6", "name": "Onion Rings", "price": 140, "veg": true, "available": false}
  ]},
  {"id": "r18", "name": "Colaba Coastal", "cuisine": "Coastal", "cityId": "mum", "area": "Colaba", "lat": 18.9067, "lon": 72.8147, "rating": 4.6, "avgPrepMinutes": 25, "menu": [
    {"id": "r18-i1", "name": "Prawn Gassi", "price": 450, "veg": false, "available": true},
    {"id": "r18-i2", "name": "Neer Dosa", "price": 90, "veg": true, "available": true},
    {"id": "r18-i3", "name": "Fish Thali", "price": 380, "veg": false, "available": true},
    {"id": "r18-i4", "name": "Kori Rotti", "price": 360, "veg": false, "available": true},
    {"id": "r18-i5", "name": "Sol Kadhi", "price": 80, "veg": true, "available": true},
    {"id": "r18-i6", "name": "Mangalore Buns", "price": 70, "veg": true, "available": false}
  ]},
  {"id": "r19", "name": "Andheri Chaat House", "cuisine": "Street Food", "cityId": "mum", "area": "Andheri", "lat": 19.1136, "lon": 72.8697, "rating": 4.4, "avgPrepMinutes": 8, "menu": [
    {"id": "r19-i1", "name": "Pani Puri", "price": 60, "veg": true, "available": true},
    {"id": "r19-i2", "name": "Pav Bhaji", "price": 140, "veg": true, "available": true},
    {"id": "r19-i3", "name": "Vada Pav", "price": 40, "veg": true, "available": true},
    {"id": "r19-i4", "name": "Sev Puri", "price": 80, "veg": true, "available": true},
    {"id": "r19-i5", "name": "Masala Chai", "price": 30, "veg": true, "available": true},
    {"id": "r19-i6", "name": "Kathi Roll", "price": 150, "veg": false, "available": false}
  ]},
  {"id": "r20", "name": "Powai Pasta Bar", "cuisine": "Italian", "cityId": "mum", "area": "Powai", "lat": 19.1176, "lon": 72.906, "rating": 4.1, "avgPrepMinutes": 20, "menu": [
    {"id": "r20-i1", "name": "Margherita Pizza", "price": 350, "veg": true, "available": true},
    {"id": "r20-i2", "name": "Penne Arrabbiata", "price": 320, "veg": true, "available": true},
    {"id": "r20-i3", "name": "Chicken Alfredo", "price": 420, "veg": false, "available": true},
    {"id": "r20-i4", "name": "Garlic Bread", "price": 150, "veg": true, "available": true},
    {"id": "r20-i5", "name": "Tiramisu", "price": 260, "veg": true, "available": true},
    {"id": "r20-i6", "name": "Truffle Risotto", "price": 520, "veg": true, "available": false}
  ]},
  {"id": "r21", "name": "Juhu Biryani House", "cuisine": "Hyderabadi", "cityId": "mum", "area": "Juhu", "lat": 19.1075, "lon": 72.8263, "rating": 4.2, "avgPrepMinutes": 25, "menu": [
    {"id": "r21-i1", "name": "Chicken Dum Biryani", "price": 320, "veg": false, "available": true},
    {"id": "r21-i2", "name": "Mutton Biryani", "price": 420, "veg": false, "available": true},
    {"id": "r21-i3", "name": "Veg Dum Biryani", "price": 240, "veg": true, "available": true},
    {"id": "r21-i4", "name": "Double Ka Meetha", "price": 120, "veg": true, "available": true},
    {"id": "r21-i5", "name": "Mirchi Ka Salan", "price": 90, "veg": true, "available": true},
    {"id": "r21-i6", "name": "Haleem", "price": 350, "veg": false, "available": false}
  ]},
  {"id": "r22", "name": "Marine Drive Desserts", "cuisine": "Desserts", "cityId": "mum", "area": "Churchgate", "lat": 18.9322, "lon": 72.8264, "rating": 4.7, "avgPrepMinutes": 10, "menu": [
    {"id": "r22-i1", "name": "Death by Chocolate", "price": 280, "veg": true, "available": true},
    {"id": "r22-i2", "name": "Red Velvet Pastry", "price": 160, "veg": true, "available": true},
    {"id": "r22-i3", "name": "Belgian Waffle", "price": 220, "veg": true, "available": true},
    {"id": "r22-i4", "name": "Kulfi Falooda", "price": 180, "veg": true, "available": true},
    {"id": "r22-i5", "name": "Cheesecake Slice", "price": 260, "veg": true, "available": true},
    {"id": "r22-i6", "name": "Brownie Sundae", "price": 240, "veg": true, "available": false}
  ]}
]
```

`config/SeedData.java`

```java
package com.quickbite.catalog.config;

import com.quickbite.catalog.domain.MenuItem;
import com.quickbite.catalog.domain.Restaurant;
import com.quickbite.catalog.domain.RestaurantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * Loads seed/restaurants.json into MongoDB.
 *   quickbite.seed.catalog=false  -> never seed (production)
 *   quickbite.seed.reset=true     -> drop and reload (after editing the JSON), and clear the Redis cache
 */
@Configuration
public class SeedData {
    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    record SeedItem(String id, String name, BigDecimal price, boolean veg, boolean available) {}
    record SeedRestaurant(String id, String name, String cuisine, String cityId, String area,
                          double lat, double lon, double rating, int avgPrepMinutes, List<SeedItem> menu) {}

    @Bean
    CommandLineRunner seed(RestaurantRepository repo, StringRedisTemplate redis, JsonMapper json,
                           @Value("${quickbite.seed.catalog:true}") boolean enabled,
                           @Value("${quickbite.seed.reset:false}") boolean reset) {
        return args -> {
            if (!enabled) return;
            if (reset) {
                repo.deleteAll();
                var keys = redis.keys("cache:*");               // fine for dev; never KEYS in production (O(N), blocks Redis)
                if (keys != null && !keys.isEmpty()) redis.delete(keys);
                log.info("Seed reset: catalog and cache cleared");
            }
            if (repo.count() > 0) return;

            SeedRestaurant[] seed;
            try (InputStream in = new ClassPathResource("seed/restaurants.json").getInputStream()) {
                seed = json.readValue(in, SeedRestaurant[].class);
            }
            var docs = java.util.Arrays.stream(seed).map(r -> new Restaurant(
                    r.id(), r.name(), r.cuisine(), r.cityId(), r.area(),
                    new GeoJsonPoint(r.lon(), r.lat()),                 // GeoJSON is [lon, lat]
                    r.rating(), r.avgPrepMinutes(),
                    "https://picsum.photos/seed/" + r.id() + "/800/400",
                    r.menu().stream().map(i -> new MenuItem(i.id(), i.name(), i.name() + " from " + r.name(),
                            i.price(), i.veg(), i.available(),
                            "https://picsum.photos/seed/" + i.id() + "/400/300")).toList()))
                .toList();
            repo.saveAll(docs);
            log.info("Seeded {} restaurants / {} menu items", docs.size(),
                    docs.stream().mapToInt(d -> d.menu().size()).sum());
        };
    }
}
```

Add to `application.yml`:

```yaml
quickbite:
  seed:
    catalog: ${SEED_CATALOG:true}
    reset: ${SEED_RESET:false}
```

**Reseeding after editing the JSON:**

```bash
SEED_RESET=true ./gradlew :services:catalog-svc:bootRun          # IDE mode
# container mode: add SEED_RESET: "true" to catalog-svc's environment once, restart, then remove it
```

---

## Step 6: Run and verify

```bash
./gradlew :services:gateway:bootRun      # terminal 1 (if not already running)
./gradlew :services:catalog-svc:bootRun  # terminal 2
```

**Layer by layer:**

```bash
# A) Direct to the service: first call MISS, second HIT (watch the latency drop)
time curl -s localhost:8081/api/restaurants | jq length     # 16 (blr)
curl -s 'localhost:8081/api/restaurants?city=mum' | jq length # 6
time curl -s localhost:8081/api/restaurants > /dev/null

# B) Redis contents
docker exec -it quickbite-redis-1 redis-cli --scan --pattern 'cache:*'
docker exec -it quickbite-redis-1 redis-cli ttl cache:restaurants:city:blr   # ~60-75: note the jitter

# C) Through NGINX: the edge cache
curl -si localhost:8000/api/restaurants | grep -i x-cache-status   # MISS
curl -si localhost:8000/api/restaurants | grep -i x-cache-status   # HIT (served by NGINX; gateway never saw it)

# D) ETag / 304
ETAG=$(curl -si localhost:8081/api/restaurants/r1 | grep -i etag | cut -d' ' -f2 | tr -d '\r')
curl -si -H "If-None-Match: $ETAG" localhost:8081/api/restaurants/r1 | head -1   # HTTP/1.1 304

# E) Invalidation on write (admin only)
ADMIN=$(scripts/token.sh admin admin)
curl -si -X PUT -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"price": 999}' localhost:8080/api/restaurants/r1/menu-items/r1-i1/price | head -1   # 204
docker exec -it quickbite-redis-1 redis-cli exists cache:restaurant:r1                    # 0 -> invalidated

# F) Role check: alice is not a restaurant -> 403
ALICE=$(scripts/token.sh alice alice)
curl -si -X PUT -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"price": 1}' localhost:8080/api/restaurants/r1/menu-items/r1-i1/price | head -1    # 403

# G) Cache metrics
curl -s -H "Authorization: Bearer $ALICE" "localhost:8081/actuator/metrics/catalog.cache" | jq

# H) Fail-open: stop Redis, the catalog still answers (slower, straight from Mongo)
docker stop quickbite-redis-1 && curl -s localhost:8081/api/restaurants | jq length && docker start quickbite-redis-1   # still 16
```

| Check | Pass condition |
|---|---|
| A | 16 `blr` + 6 `mum` restaurants; the second call is faster |
| C | `MISS`, then `HIT` |
| D | `304` |
| E/F | `204` and key gone / `403` |
| H | Still returns 16 (logs show "cache read failed, falling back to DB") |

**Checkpoint:**

1. After you changed the price in step E, what did the phone see for the next 60 s, and why is that acceptable for browsing but not for checkout?
2. What would happen during a stampede *without* the single-flight lock? Try it: `hey -n 2000 -c 200 localhost:8081/api/restaurants/r2` right after `redis-cli del cache:restaurant:r2`, and compare the `miss` counter with and without the lock.
