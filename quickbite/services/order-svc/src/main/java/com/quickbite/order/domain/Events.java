package com.quickbite.order.domain;

import java.math.BigDecimal;

public final class Events {
    public static final String ORDERS_TOPIC = "orders.events";
    public static final String PAYMENTS_TOPIC = "payments.events";
    private Events() {}

    public record OrderCreated(String orderId, String customerId, String restaurantId,
                               BigDecimal amount, String currency, double deliveryLat, double deliveryLon,
                               String paymentMethodId) {}
    public record OrderStatusChanged(String orderId, String customerId, String status, String riderId,
                                     double deliveryLat, double deliveryLon) {}
    public record PaymentEvent(String orderId, String paymentId, String reason) {}

    public static OrderStatusChanged statusOf(Order o) {
        return new OrderStatusChanged(o.getId().toString(), o.getCustomerId(), o.getStatus().name(),
                o.getRiderId(), o.getDeliveryLat(), o.getDeliveryLon());
    }
}