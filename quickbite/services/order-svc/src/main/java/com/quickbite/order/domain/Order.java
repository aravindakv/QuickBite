package com.quickbite.order.domain;

import jakarta.persistence.*;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {
    @Id private Long id;
    private String customerId;
    private String restaurantId;
    @Enumerated(EnumType.STRING) private OrderStatus status;
    private BigDecimal totalAmount;
    private String currency;
    private String riderId;
    private double deliveryLat;
    private double deliveryLon;
    private String clientRequestId;
    @Version private long version;
    private Instant createdAt;
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {} // for JPA

    @SuppressWarnings("null")
    public static Order place(long id, String customerId, String restaurantId, List<OrderLine> lines,
                              double lat, double lon, String clientRequestId) {
        if (lines.isEmpty()) throw new IllegalArgumentException("order must contain at least one item");
        var o = new Order();
        o.id = id; o.customerId = customerId; o.restaurantId = restaurantId;
        o.lines = new ArrayList<>(lines);
        o.totalAmount = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        o.currency = "INR";
        o.deliveryLat = lat; o.deliveryLon = lon;
        o.clientRequestId = clientRequestId;
        o.status = OrderStatus.PENDING_PAYMENT;
        o.createdAt = o.updatedAt = Instant.now();
        return o;
    }

    public void transitionTo(OrderStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Order %d cannot move from %s to %s".formatted(id, status, next));
        }
        status = next;
        updatedAt = Instant.now();
    }

    public void assignRider(String riderId) { transitionTo(OrderStatus.RIDER_ASSIGNED); this.riderId = riderId; }

    public void requireRider(String riderId) {
        if (!riderId.equals(this.riderId)) throw new AccessDeniedException("not your delivery");
    }

    public boolean visibleTo(String userId, boolean admin) {
        return admin || userId.equals(customerId) || userId.equals(riderId);
    }

    public Long getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getRestaurantId() { return restaurantId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getRiderId() { return riderId; }
    public double getDeliveryLat() { return deliveryLat; }
    public double getDeliveryLon() { return deliveryLon; }
    public List<OrderLine> getLines() { return List.copyOf(lines); }
    public Instant getCreatedAt() { return createdAt; }
}