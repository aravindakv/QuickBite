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