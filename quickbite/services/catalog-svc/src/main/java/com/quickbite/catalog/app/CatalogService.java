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