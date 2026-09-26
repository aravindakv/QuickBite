package com.quickbite.order.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, RIDER_ASSIGNED, PICKED_UP, DELIVERED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED),
            PAID,            EnumSet.of(RIDER_ASSIGNED, CANCELLED),
            RIDER_ASSIGNED,  EnumSet.of(PICKED_UP),
            PICKED_UP,       EnumSet.of(DELIVERED),
            DELIVERED,       EnumSet.noneOf(OrderStatus.class),
            CANCELLED,       EnumSet.noneOf(OrderStatus.class));

    public boolean canMoveTo(OrderStatus next) { return ALLOWED.get(this).contains(next); }
    public boolean isActiveDelivery() { return this == RIDER_ASSIGNED || this == PICKED_UP; }
}