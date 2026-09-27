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
    public record TrackPoint(double lat, double lon, long ts) {}

    /** Declarative HTTP client: Spring generates the implementation from this interface. */
    @HttpExchange("/api/restaurants")
    public interface CatalogClient {
        @GetExchange("/{restaurantId}/menu-items")
        List<MenuItemPrice> prices(@PathVariable String restaurantId, @RequestParam List<String> ids);
    }

    /** NOTE: the prefix is "/internal", so every rider method starts with "/riders". */
    @HttpExchange("/internal")
    public interface LocationClient {
        @GetExchange("/riders/nearest")
        List<RiderCandidate> nearest(@RequestParam double lat, @RequestParam double lon,
                                     @RequestParam double radiusKm, @RequestParam int limit);

        @PostExchange("/riders/{riderId}/claim")
        ClaimResult claim(@PathVariable String riderId, @RequestParam String orderId);

        @PostExchange("/riders/{riderId}/release")
        void release(@PathVariable String riderId, @RequestParam String orderId);

        /** The rider's trail for one order (file 20). */
        @GetExchange("/tracks/{orderId}")
        List<TrackPoint> track(@PathVariable String orderId);
    }
}
