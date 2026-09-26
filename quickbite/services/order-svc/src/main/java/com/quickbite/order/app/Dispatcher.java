package com.quickbite.order.app;

import com.quickbite.common.health.LoopWatchdog;
import com.quickbite.order.client.Clients.*;
import com.quickbite.order.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private final OrderRepository repo;
    private final LocationClient location;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final OrderService orders;
    private final LoopWatchdog watchdog;
    private final DebugSwitch debug;
    private final double radiusKm;

    public Dispatcher(OrderRepository repo, LocationClient location, CircuitBreakerFactory<?, ?> breakers, OrderService orders,
                      LoopWatchdog watchdog, DebugSwitch debug, @Value("${quickbite.dispatch.radius-km}") double radiusKm) {
        this.repo = repo; this.location = location; this.breakers = breakers; this.orders = orders;
        this.watchdog = watchdog; this.debug = debug; this.radiusKm = radiusKm;
    }

    @Scheduled(fixedDelayString = "${quickbite.dispatch.interval-ms}")
    public void dispatch() {
        watchdog.beat("dispatcher");
        while (debug.hang()) { sleep(1000); }   // DEBUG: simulated hang -> watchdog should trip after 30 s

        for (Order o : repo.findTop20ByStatusOrderByCreatedAtAsc(OrderStatus.PAID)) {
            String orderId = o.getId().toString();
            var cb = breakers.create("location");
            List<RiderCandidate> candidates = cb.run(
                    () -> location.nearest(o.getDeliveryLat(), o.getDeliveryLon(), radiusKm, 5),
                    t -> { log.warn("location-svc unavailable: {}", t.toString()); return List.of(); });

            for (RiderCandidate c : candidates) {
                boolean claimed = cb.run(() -> location.claim(c.riderId(), orderId).claimed(), t -> false);
                if (!claimed) continue;                       // someone else got this rider; try the next
                try {
                    orders.assignRider(o.getId(), c.riderId());
                    log.info("Order {} assigned to rider {} ({} km)", orderId, c.riderId(), "%.2f".formatted(c.distanceKm()));
                } catch (RuntimeException raceLost) {         // optimistic lock / illegal transition
                    log.info("Order {} already assigned elsewhere; releasing rider {}", orderId, c.riderId());
                    cb.run(() -> { location.release(c.riderId(), orderId); return null; }, t -> null);
                }
                break;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
