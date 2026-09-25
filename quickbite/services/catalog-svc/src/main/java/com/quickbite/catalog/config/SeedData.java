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
                try {
                    var keys = redis.keys("cache:*");           // fine for dev; never KEYS in production (O(N), blocks Redis)
                    if (keys != null && !keys.isEmpty()) redis.delete(keys);
                } catch (Exception e) {
                    log.warn("Seed reset: cache clear skipped, Redis unreachable: {}", e.toString());
                }
                log.info("Seed reset: catalog cleared");
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