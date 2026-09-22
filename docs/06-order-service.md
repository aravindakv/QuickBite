# 06 — order-svc: State Machine, Outbox, Saga, Dispatcher

**Goal:** the core business service. By the end of this file, placing an order does the following:

1. Prices the items **server-side** by calling catalog-svc. The call is authenticated with a service token and guarded by a circuit breaker.
2. Saves the order and its `order.created` event **atomically** (outbox).
3. Supports safe client retries with an `Idempotency-Key` header.
4. Reacts to payment events (the saga).
5. Runs a **dispatcher** loop that assigns the nearest free rider using a distributed claim.

---

## Concepts first

### The order state machine

```
PENDING_PAYMENT ──payment.authorized──► PAID ──rider claimed──► RIDER_ASSIGNED ──pickup──► PICKED_UP ──deliver──► DELIVERED
      │                                   │
      └──payment.failed──► CANCELLED ◄────┘ (admin/timeout)
```

All transitions are enforced in **one place**, `OrderStatus.canMoveTo`. Illegal moves throw an exception and become HTTP `409 Conflict`. The `@Version` column adds **optimistic locking**: if two replicas update the same order at once, one of them fails instead of silently overwriting the other.

### Saga (choreography)

There is no distributed transaction across order-svc and payment-svc. Each service does a **local** transaction and emits an event, and other services react to it. When something fails later, a **compensating** event undoes earlier steps: `payment.failed` → order `CANCELLED`. File 07 is the other half of this dance.

### Never make remote calls inside a DB transaction

A transaction holds a pooled connection, and often row locks, for its whole duration. If catalog-svc takes 2 s, you hold a connection for 2 s. With 10 connections, 5 concurrent orders could exhaust the pool. So we **price first, then open a short transaction**.

### Payment method pass-through (and why order-svc never sees card numbers)

The request carries only `paymentMethodId`, an opaque token like `pm_7f3a…` created by payment-svc (file 07). order-svc copies it into `order.created` without interpreting it. payment-svc then checks that the token belongs to this customer. Keeping card data out of order-svc keeps order-svc **out of PCI-DSS scope**, which shrinks your audit surface.

### Idempotency keys

Mobile networks drop responses. If the phone retries "place order" after a timeout, it must not create a second order. The client sends `Idempotency-Key: <uuid>` once per checkout. It is stored in a unique column, and a retry with the same key returns the existing order.

---

## Step 1: Build and config

`services/order-svc/build.gradle.kts`

```kotlin
plugins { id("quickbite.spring-service") }

dependencies {
    implementation(project(":libs:common"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}
```

`services/order-svc/src/main/resources/application.yml`

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
  application:
    name: order-svc
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/orders
    username: quickbite
    password: quickbite
    hikari:
      maximum-pool-size: 10        # small pools are FASTER (less contention); measure in file 12
  jpa:
    open-in-view: false            # no lazy loading in controllers -> no hidden queries after the tx
    hibernate:
      ddl-auto: validate           # Flyway owns the schema; Hibernate only checks it
  kafka:
    consumer:
      group-id: order-svc
  cloud:
    circuitbreaker:
      resilience4j:
        disable-thread-pool: true  # run on the caller's thread -> MDC (session id) survives; timeouts come from the HTTP client

server:
  port: 8082

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,db

quickbite:
  outbox:
    enabled: true
  catalog-url: ${CATALOG_URL:http://localhost:8081}
  location-url: ${LOCATION_URL:http://localhost:8084}
  service-auth:
    client-id: order-svc
    client-secret: ${ORDER_SVC_SECRET:order-svc-secret}
  dispatch:
    interval-ms: 3000
    radius-km: 5

resilience4j:
  circuitbreaker:
    instances:
      catalog:
        sliding-window-size: 20
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
      location:
        sliding-window-size: 20
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

`src/main/resources/db/migration/V1__init.sql`

```sql
create table orders (
  id                bigint primary key,              -- Snowflake id
  customer_id       varchar(64)  not null,
  restaurant_id     varchar(64)  not null,
  status            varchar(32)  not null,
  total_amount      numeric(12,2) not null,
  currency          varchar(3)   not null default 'INR',
  rider_id          varchar(64),
  delivery_lat      double precision not null,
  delivery_lon      double precision not null,
  client_request_id varchar(64) unique,              -- Idempotency-Key
  version           bigint not null default 0,       -- optimistic locking
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index idx_orders_customer on orders(customer_id, created_at desc);
create index idx_orders_rider on orders(rider_id) where rider_id is not null;
create index idx_orders_paid on orders(created_at) where status = 'PAID';   -- partial index: dispatcher's hot query

create table order_lines (
  order_id     bigint not null references orders(id),
  menu_item_id varchar(64)  not null,
  name         varchar(200) not null,
  quantity     int not null check (quantity > 0),
  unit_price   numeric(10,2) not null
);
create index idx_order_lines_order on order_lines(order_id);

create table outbox (
  id uuid primary key, topic varchar(200) not null, msg_key varchar(200) not null,
  event_type varchar(100) not null, payload text not null,
  session_id varchar(100), correlation_id varchar(100),
  created_at timestamptz not null default now(), published_at timestamptz
);
create index idx_outbox_unpublished on outbox(created_at) where published_at is null;

create table processed_events (event_id varchar(64) primary key, processed_at timestamptz not null default now());
```

**Why partial indexes:** `idx_orders_paid` only contains rows where `status = 'PAID'`, which at any moment is a handful. The dispatcher's query therefore stays O(small) even with 100 million historical orders.

---

## Step 2: Domain

All Java lives under `services/order-svc/src/main/java/com/quickbite/order/`.

`OrderApplication.java`

```java
package com.quickbite.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }
}
```

`domain/OrderStatus.java`

```java
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
```

`domain/OrderLine.java`

```java
package com.quickbite.order.domain;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

@Embeddable
public record OrderLine(String menuItemId, String name, int quantity, BigDecimal unitPrice) {
    public BigDecimal lineTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
```

`domain/Order.java`

```java
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
```

`domain/OrderRepository.java`

```java
package com.quickbite.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByClientRequestId(String clientRequestId);
    List<Order> findTop20ByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Order> findTop20ByStatusOrderByCreatedAtAsc(OrderStatus status);
    Optional<Order> findFirstByRiderIdAndStatusIn(String riderId, List<OrderStatus> statuses);
}
```

`domain/Events.java`: event contracts. Ids travel as **strings**.

```java
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
```

---

## Step 3: Outbound clients (service token + timeouts + circuit breaker)

`client/Clients.java`

```java
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
```

`client/ClientConfig.java`

```java
package com.quickbite.order.client;

import com.quickbite.common.context.PropagatingInterceptor;
import com.quickbite.common.security.ServiceAuthInterceptor;
import com.quickbite.common.security.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ClientConfig {

    @Bean
    ServiceTokenProvider serviceTokenProvider(@Value("${quickbite.service-auth.token-uri}") String tokenUri,
                                              @Value("${quickbite.service-auth.client-id}") String clientId,
                                              @Value("${quickbite.service-auth.client-secret}") String secret) {
        return new ServiceTokenProvider(tokenUri, clientId, secret);
    }

    @Bean
    Clients.CatalogClient catalogClient(@Value("${quickbite.catalog-url}") String url, ServiceTokenProvider tokens) {
        return create(url, tokens, Clients.CatalogClient.class);
    }

    @Bean
    Clients.LocationClient locationClient(@Value("${quickbite.location-url}") String url, ServiceTokenProvider tokens) {
        return create(url, tokens, Clients.LocationClient.class);
    }

    private static <T> T create(String baseUrl, ServiceTokenProvider tokens, Class<T> type) {
        // TIMEOUTS ARE MANDATORY. A call without a timeout can hang a thread forever,
        // and the circuit breaker can only count failures that actually finish.
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(2));
        RestClient rc = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new ServiceAuthInterceptor(tokens))
                .requestInterceptor(new PropagatingInterceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(type);
    }
}
```

**How the circuit breaker works (Resilience4j):**

- **CLOSED:** calls pass through, and the breaker records the outcomes of the last 20 calls.
- **OPEN:** once ≥ 50% of those calls fail, **every call fails instantly** for 10 s and returns a fallback. No thread waits on a dead service, and the struggling service gets breathing room to recover.
- **HALF_OPEN:** after 10 s, a few trial calls are allowed. If they succeed, the breaker closes again; if they fail, it re-opens.

---

## Step 4: Application service

`app/PlaceOrderRequest.java`

```java
package com.quickbite.order.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record PlaceOrderRequest(
        @NotBlank String restaurantId,
        @NotEmpty @Size(max = 50) List<@Valid Item> items,
        @DecimalMin("-90") @DecimalMax("90") double deliveryLat,
        @DecimalMin("-180") @DecimalMax("180") double deliveryLon,
        @Size(max = 40) String paymentMethodId) {      // optional: null -> the customer's default card (file 07)
    public record Item(@NotBlank String menuItemId, @Min(1) @Max(20) int quantity) {}
    // Note: NO price field. Clients never tell the server what things cost.
    // Note: NO card number either. Only an opaque token id ("pm_..."); card data never touches order-svc.
}
```

`app/OrderService.java`

```java
package com.quickbite.order.app;

import com.quickbite.common.id.SnowflakeIdGenerator;
import com.quickbite.common.outbox.IdempotencyGuard;
import com.quickbite.common.outbox.OutboxWriter;
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
    private final OrderRepository repo;
    private final CatalogClient catalog;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final OutboxWriter outbox;
    private final IdempotencyGuard idempotency;
    private final SnowflakeIdGenerator ids;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository repo, CatalogClient catalog, CircuitBreakerFactory<?, ?> breakers,
                        OutboxWriter outbox, IdempotencyGuard idempotency, SnowflakeIdGenerator ids, TransactionTemplate tx) {
        this.repo = repo; this.catalog = catalog; this.breakers = breakers; this.outbox = outbox;
        this.idempotency = idempotency; this.ids = ids; this.tx = tx;
    }

    public Order place(String customerId, PlaceOrderRequest req, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = repo.findByClientRequestId(idempotencyKey);
            if (existing.isPresent()) return existing.get();          // safe retry: same answer
        }

        // 1) Remote call OUTSIDE any transaction, behind a circuit breaker
        List<String> itemIds = req.items().stream().map(PlaceOrderRequest.Item::menuItemId).toList();
        List<MenuItemPrice> prices = breakers.create("catalog").run(
                () -> catalog.prices(req.restaurantId(), itemIds),
                t -> { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Menu temporarily unavailable, please retry"); });
        Map<String, MenuItemPrice> byId = prices.stream().collect(Collectors.toMap(MenuItemPrice::id, Function.identity()));

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
            outbox.write(Events.ORDERS_TOPIC, o.getId().toString(), "order.created",
                    new OrderCreated(o.getId().toString(), customerId, o.getRestaurantId(),
                            o.getTotalAmount(), o.getCurrency(), o.getDeliveryLat(), o.getDeliveryLon(),
                            req.paymentMethodId()));      // passed through untouched; payment-svc validates ownership
            return o;
        });
    }

    /** Saga step: react to payment-svc. Idempotent: a redelivered event changes nothing. */
    public void onPaymentEvent(String eventId, String eventType, PaymentEvent e) {
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(eventId)) return;
            Order o = repo.findById(Long.parseLong(e.orderId())).orElseThrow();
            switch (eventType) {
                case "payment.authorized" -> o.transitionTo(OrderStatus.PAID);
                case "payment.failed" -> o.transitionTo(OrderStatus.CANCELLED); // compensation
                default -> { return; } // e.g. payment.captured: nothing to do here
            }
            publishStatus(o);
        });
    }

    public void assignRider(long orderId, String riderId) {
        tx.executeWithoutResult(s -> {
            Order o = repo.findById(orderId).orElseThrow();
            o.assignRider(riderId);          // throws if another replica already assigned it
            publishStatus(o);
        });
    }

    public Order riderAction(long orderId, String riderId, OrderStatus next) {
        return tx.execute(s -> {
            Order o = repo.findById(orderId).orElseThrow(() -> new NoSuchElementException("order " + orderId));
            o.requireRider(riderId);
            o.transitionTo(next);
            publishStatus(o);
            return o;
        });
    }

    private void publishStatus(Order o) {
        outbox.write(Events.ORDERS_TOPIC, o.getId().toString(), "order.status-changed", Events.statusOf(o));
    }
}
```

---

## Step 5: The dispatcher (geo search + distributed claim)

**The race it must survive:** two replicas of order-svc run the dispatcher. Both see order 42 as PAID, and both find rider bob as nearest.

**Defenses:**

1. **Rider claim:** location-svc does `SET rider:busy:bob 42 NX`. Only one claim for bob ever succeeds, so a rider never gets two orders.
2. **Order version:** if both replicas claimed *different* riders for order 42, the second `assignRider` fails on `@Version` (or on the state machine). We then **release** the claimed rider, which is the compensation step.

`app/DebugSwitch.java`: lets you simulate a hung loop, used to prove the watchdog works.

```java
package com.quickbite.order.app;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DebugSwitch {
    private final AtomicBoolean hangDispatcher = new AtomicBoolean(false);
    public boolean hang() { return hangDispatcher.get(); }
    public void setHang(boolean on) { hangDispatcher.set(on); }
}
```

`app/Dispatcher.java`

```java
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

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
```

---

## Step 6: Kafka listener and topics

`messaging/PaymentEventsListener.java`

```java
package com.quickbite.order.messaging;

import com.quickbite.common.context.Headers;
import com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration;
import com.quickbite.order.app.OrderService;
import com.quickbite.order.domain.Events;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentEventsListener {
    private final OrderService orders;
    private final JsonMapper json;

    public PaymentEventsListener(OrderService orders, JsonMapper json) { this.orders = orders; this.json = json; }

    @KafkaListener(topics = Events.PAYMENTS_TOPIC)
    public void on(ConsumerRecord<String, String> rec) {
        String eventId = KafkaErrorHandlingAutoConfiguration.header(rec, Headers.K_EVENT_ID);
        String type = KafkaErrorHandlingAutoConfiguration.header(rec, Headers.K_EVENT_TYPE);
        orders.onPaymentEvent(eventId, type, json.readValue(rec.value(), Events.PaymentEvent.class));
    }
}
```

> **Don't `beat()` the watchdog from listeners.** A listener runs only when messages arrive, so a quiet topic would look "stuck". Beat only loops that run on a schedule.

`messaging/TopicConfig.java`

```java
package com.quickbite.order.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Each service declares every topic it touches. KafkaAdmin creates missing ones at startup (idempotent). */
@Configuration
public class TopicConfig {
    @Bean
    KafkaAdmin.NewTopics orderTopics(@Value("${KAFKA_PARTITIONS:3}") int partitions, @Value("${KAFKA_REPLICAS:1}") short replicas) {
        return new KafkaAdmin.NewTopics(
                topic("orders.events", partitions, replicas),
                topic("payments.events", partitions, replicas),
                topic("payments.events.dlt", partitions, replicas));   // DLT for the topic WE consume
    }
    private static NewTopic topic(String name, int p, short r) { return TopicBuilder.name(name).partitions(p).replicas(r).build(); }
}
```

**Partition keys matter:** events are keyed by `orderId`. Kafka guarantees order **within a partition**, and the same key always goes to the same partition. So `order.created` → `PAID` → `RIDER_ASSIGNED` for one order are always consumed in order, while different orders spread across partitions for parallelism.

---

## Step 7: REST API

`api/OrderResponse.java`

```java
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
```

`api/OrderController.java`

```java
package com.quickbite.order.api;

import com.quickbite.order.app.*;
import com.quickbite.order.domain.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    private final OrderRepository repo;
    private final DebugSwitch debug;

    public OrderController(OrderService service, OrderRepository repo, DebugSwitch debug) {
        this.service = service; this.repo = repo; this.debug = debug;
    }

    @PostMapping
    @PreAuthorize("hasRole('customer')")
    public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody PlaceOrderRequest req) {
        Order o = service.place(jwt.getSubject(), req, key);
        return ResponseEntity.created(URI.create("/api/orders/" + o.getId())).body(OrderResponse.from(o));
    }

    @GetMapping
    public List<OrderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return repo.findTop20ByCustomerIdOrderByCreatedAtDesc(jwt.getSubject()).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        Order o = repo.findById(id).orElseThrow(() -> new NoSuchElementException("order " + id));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_admin"));
        // Object-level authorization: stops "BOLA" (alice reading bob's order by guessing ids)
        if (!o.visibleTo(jwt.getSubject(), admin)) throw new AccessDeniedException("not your order");
        return OrderResponse.from(o);
    }

    @GetMapping("/rider/active")
    @PreAuthorize("hasRole('rider')")
    public ResponseEntity<OrderResponse> activeForRider(@AuthenticationPrincipal Jwt jwt) {
        return repo.findFirstByRiderIdAndStatusIn(jwt.getSubject(), List.of(OrderStatus.RIDER_ASSIGNED, OrderStatus.PICKED_UP))
                .map(o -> ResponseEntity.ok(OrderResponse.from(o)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/pickup")
    @PreAuthorize("hasRole('rider')")
    public OrderResponse pickup(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return OrderResponse.from(service.riderAction(id, jwt.getSubject(), OrderStatus.PICKED_UP));
    }

    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasRole('rider')")
    public OrderResponse deliver(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return OrderResponse.from(service.riderAction(id, jwt.getSubject(), OrderStatus.DELIVERED));
    }

    /** Chaos helper for file 09/11: freezes the dispatcher loop. */
    @PostMapping("/admin/debug/hang")
    @PreAuthorize("hasRole('admin')")
    public String hang(@RequestParam boolean on) { debug.setHang(on); return "dispatcher hang=" + on; }
}
```

---

## Step 8: Run and verify

Start catalog-svc and the gateway (as in file 05), then run:

```bash
./gradlew :services:order-svc:bootRun
```

```bash
ALICE=$(scripts/token.sh alice alice)
KEY=$(uuidgen)

# Place an order (note: we send NO prices)
curl -s -X POST localhost:8000/api/orders \
  -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i2","quantity":2}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | tee /tmp/order.json | jq
ORDER=$(jq -r .id /tmp/order.json)

# Retry with the SAME key -> same order id, no duplicate
curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i2","quantity":2}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | jq -r .id

# The event on Kafka, with its headers (eventId, eventType, sessionId, correlationId)
docker exec -it quickbite-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders.events --from-beginning --property print.headers=true --property print.key=true --timeout-ms 5000

# The outbox row is marked published
docker exec -it quickbite-postgres-1 psql -U quickbite -d orders -c "select event_type, published_at is not null as sent from outbox;"

# BOLA check: bob cannot read alice's order
BOB=$(scripts/token.sh bob bob)
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $BOB" localhost:8000/api/orders/$ORDER   # 403

# Outbox resilience: Kafka down, orders still accepted; events flush after Kafka returns
docker stop quickbite-kafka-1
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8000/api/orders -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.93,"deliveryLon":77.62}'
docker exec -it quickbite-postgres-1 psql -U quickbite -d orders -c "select count(*) from outbox where published_at is null;"  # 1
docker start quickbite-kafka-1   # wait ~20 s, re-run the count -> 0

# Circuit breaker: stop catalog-svc (Ctrl+C), place orders -> fast 503 once the breaker opens (no 2 s waits)
```

| Check | Pass condition |
|---|---|
| Place | `201`, `status: PENDING_PAYMENT`, total = 2 × server price |
| Retry same key | Same `id` |
| Kafka | `order.created` with headers, including `sessionId` |
| BOLA | `403` |
| Kafka down | `201`; unpublished count goes 1 → 0 after restart |
| Catalog down | The first calls take ~1–2 s; after 5 failures, calls return `503` instantly |

The order stays in `PENDING_PAYMENT` until payment-svc exists. That comes next.

**Checkpoint:**

1. Why is `Idempotency-Key` checked *before* calling catalog-svc?
2. What exactly happens if the process crashes after `kafka.send()` but before `update outbox set published_at`? Who protects you from the duplicate event?
