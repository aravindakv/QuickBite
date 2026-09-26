package com.quickbite.order.api;

import com.quickbite.order.domain.Order;
import com.quickbite.order.domain.OrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(String id, String restaurantId, String status, BigDecimal total, String currency,
                            String riderId, double deliveryLat, double deliveryLon, List<OrderLine> lines, Instant createdAt) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getId().toString(), o.getRestaurantId(), o.getStatus().name(), o.getTotalAmount(),
                o.getCurrency(), o.getRiderId(), o.getDeliveryLat(), o.getDeliveryLon(), o.getLines(), o.getCreatedAt());
    }
}