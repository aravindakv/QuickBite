package com.quickbite.payment.domain;

import java.math.BigDecimal;

public final class Events {
    public static final String ORDERS_TOPIC = "orders.events";
    public static final String PAYMENTS_TOPIC = "payments.events";
    private Events() {}

    // Consumed. Must match order-svc's JSON (a "contract": file 11 adds contract tests).
    public record OrderCreated(String orderId, String customerId, String restaurantId,
                               BigDecimal amount, String currency, double deliveryLat, double deliveryLon,
                               String paymentMethodId) {}
    public record OrderStatusChanged(String orderId, String customerId, String status, String riderId,
                                     double deliveryLat, double deliveryLon) {}
    // Produced
    public record PaymentEvent(String orderId, String paymentId, String reason) {}
}