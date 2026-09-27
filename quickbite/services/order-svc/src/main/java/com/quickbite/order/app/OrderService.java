package com.quickbite.order.app;

import com.quickbite.common.context.RequestContext;
import com.quickbite.common.id.SnowflakeIdGenerator;
import com.quickbite.common.outbox.IdempotencyGuard;
import com.quickbite.common.outbox.OutboxWriter;
import com.quickbite.order.api.TrackResponse;
import com.quickbite.order.client.Clients.*;
import com.quickbite.order.domain.*;
import com.quickbite.order.domain.Events.*;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {
    /** City two-wheeler average including stops. Crude on purpose: see file 20. */
    private static final double AVERAGE_SPEED_KMH = 18;

    private final OrderRepository repo;
    private final CatalogClient catalog;
    private final LocationClient location;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final OutboxWriter outbox;
    private final IdempotencyGuard idempotency;
    private final SnowflakeIdGenerator ids;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository repo, CatalogClient catalog, LocationClient location,
                        CircuitBreakerFactory<?, ?> breakers, OutboxWriter outbox, IdempotencyGuard idempotency,
                        SnowflakeIdGenerator ids, TransactionTemplate tx) {
        this.repo = repo; this.catalog = catalog; this.location = location; this.breakers = breakers;
        this.outbox = outbox; this.idempotency = idempotency; this.ids = ids; this.tx = tx;
    }

    public Order place(String customerId, PlaceOrderRequest req, String idempotencyKey, RequestContext ctx) {
        if (idempotencyKey != null) {
            var existing = repo.findByClientRequestId(idempotencyKey);
            if (existing.isPresent()) return existing.get();          // safe retry: same answer
        }

        // 1) Remote call OUTSIDE any transaction, behind a circuit breaker
        List<String> itemIds = req.items().stream().map(PlaceOrderRequest.Item::menuItemId).toList();
        List<MenuItemPrice> prices = breakers.create("catalog").run(
                () -> catalog.prices(req.restaurantId(), itemIds),
                t -> { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Menu temporarily unavailable, please retry"); });
        Map<String, MenuItemPrice> byId = prices.stream()
                .collect(Collectors.toMap(MenuItemPrice::id, Function.identity()));

        List<OrderLine> lines = new ArrayList<>();
        for (var item : req.items()) {
            MenuItemPrice p = byId.get(item.menuItemId());
            if (p == null) throw new IllegalArgumentException("unknown item " + item.menuItemId());
            if (!p.available()) throw new IllegalStateException(p.name() + " is sold out");
            lines.add(new OrderLine(p.id(), p.name(), item.quantity(), p.price()));
        }

        // 2) Short local transaction: order + event commit together, or not at all
        return tx.execute(status -> {
            Order o = Order.place(ids.nextId(), customerId, req.restaurantId(), lines,
                    req.deliveryLat(), req.deliveryLon(), idempotencyKey);
            repo.save(o);
            outbox.write(ctx, Events.ORDERS_TOPIC, o.getId().toString(), "order.created",
                    new OrderCreated(o.getId().toString(), customerId, o.getRestaurantId(),
                            o.getTotalAmount(), o.getCurrency(), o.getDeliveryLat(), o.getDeliveryLon(),
                            req.paymentMethodId()));
            return o;
        });
    }

    /** Saga step: react to payment-svc. Idempotent: a redelivered event changes nothing. */
    public void onPaymentEvent(String eventId, String eventType, PaymentEvent e, RequestContext ctx) {
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(eventId)) return;
            Order o = repo.findById(Long.parseLong(e.orderId())).orElseThrow();
            switch (eventType) {
                case "payment.authorized" -> o.transitionTo(OrderStatus.PAID);
                case "payment.failed" -> o.transitionTo(OrderStatus.CANCELLED);   // compensation
                default -> { return; }
            }
            publishStatus(o, ctx);
        });
    }

    /** Called by the scheduled dispatcher: there is no user request behind it. */
    public void assignRider(long orderId, String riderId) {
        tx.executeWithoutResult(s -> {
            Order o = repo.findById(orderId).orElseThrow();
            o.assignRider(riderId);
            publishStatus(o, RequestContext.NONE);
        });
    }

    public Order riderAction(long orderId, String riderId, OrderStatus next, RequestContext ctx) {
        return tx.execute(s -> {
            Order o = repo.findById(orderId).orElseThrow(() -> new NoSuchElementException("order " + orderId));
            o.requireRider(riderId);
            o.transitionTo(next);
            publishStatus(o, ctx);
            return o;
        });
    }

    /** Live tracking (file 20). Best-effort: a tracking failure must never break the screen. */
    public TrackResponse track(Order o) {
        String orderId = o.getId().toString();
        TrackPoint destination = new TrackPoint(o.getDeliveryLat(), o.getDeliveryLon(), 0L);
        if (!o.getStatus().isActiveDelivery()) {                    // not out for delivery: nothing to draw yet
            return new TrackResponse(orderId, o.getStatus().name(), o.getRiderId(),
                    List.of(), null, destination, null, null);
        }
        // Explicit type witness: without it, javac infers T from the fallback and fails to match List<TrackPoint>.
        List<TrackPoint> path = breakers.create("location").<List<TrackPoint>>run(
                () -> location.track(orderId),
                t -> List.of());
        TrackPoint rider = path.isEmpty() ? null : path.get(path.size() - 1);
        Double km = rider == null ? null
                : round(distanceKm(rider.lat(), rider.lon(), destination.lat(), destination.lon()));
        Integer eta = km == null ? null : (int) Math.max(1, Math.ceil(km / AVERAGE_SPEED_KMH * 60));
        return new TrackResponse(orderId, o.getStatus().name(), o.getRiderId(), path, rider, destination, km, eta);
    }

    private void publishStatus(Order o, RequestContext ctx) {
        outbox.write(ctx, Events.ORDERS_TOPIC, o.getId().toString(), "order.status-changed", Events.statusOf(o));
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    /** Same equirectangular approximation as location-svc. */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dx = (lon2 - lon1) * 111.32 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double dy = (lat2 - lat1) * 110.57;
        return Math.hypot(dx, dy);
    }
}
