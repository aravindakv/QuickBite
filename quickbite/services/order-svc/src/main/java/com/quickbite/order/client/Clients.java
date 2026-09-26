package com.quickbite.order.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.math.BigDecimal;
import java.util.List;

public final class Clients {
    private Clients() {}

    public record MenuItemPrice(String id, String name, BigDecimal price, boolean available) {}
    public record RiderCandidate(String riderId, double distanceKm) {}
    public record ClaimResult(boolean claimed) {}

    /** Declarative HTTP client: Spring generates the implementation from this interface. */
    @HttpExchange("/api/restaurants")
    public interface CatalogClient {
        @GetExchange("/{restaurantId}/menu-items")
        List<MenuItemPrice> prices(@PathVariable String restaurantId, @RequestParam List<String> ids);
    }

    @HttpExchange("/internal/riders")
    public interface LocationClient {
        @GetExchange("/nearest")
        List<RiderCandidate> nearest(@RequestParam double lat, @RequestParam double lon,
                                     @RequestParam double radiusKm, @RequestParam int limit);
        
        @PostExchange("/{riderId}/claim")
        ClaimResult claim(@PathVariable String riderId, @RequestParam String orderId);
        
        @PostExchange("/{riderId}/release")
        void release(@PathVariable String riderId, @RequestParam String orderId);
    }
}