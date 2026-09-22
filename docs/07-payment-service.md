# 07 — payment-svc: Fake Cards, Tokenization, Circuit Breaker, Retry, Idempotency, Dead Letters

**Goal:** the other half of the saga, with a **realistic fake payment provider (PSP)**. payment-svc does the following:

- Lets customers **save cards**. The PSP tokenizes them, and payment-svc stores only a token, the brand and the last 4 digits.
- Supports **Stripe-style test card numbers** that approve, decline, fail transiently, respond slowly, or fail at capture.
- Consumes `order.created` and authorizes the payment against the chosen card (or the customer's default card).
- Publishes `payment.authorized` or `payment.failed` (with a decline reason), and captures the payment when the order is delivered.

You will pay with each test card and watch retries, the circuit breaker, compensation and dead letters react.

---

## Concepts first

### Card data and PCI-DSS: tokenization

Anything that stores, processes or transmits a full card number (PAN) is **in PCI scope**, which means audits, network segmentation and pen tests. The industry fix is **tokenization**:

```
card number ──► PSP (tokenize) ──► "tok_9f2c…"  (opaque; useless outside that PSP)
                                         │
payment-svc stores:  pm_ab12 → { token, brand: VISA, last4: 4242, exp: 12/2030 }
order-svc sees only: pm_ab12
```

**The rules this code follows:**

- The **PAN is never stored or logged**; logs show `****4242`.
- The **CVC is never stored at all** (PCI forbids it after authorization). It's checked at tokenization, then discarded.
- Only the PSP (our `FakePsp`, with its own `psp_fake_vault` table standing in for the provider's systems) maps a token to card behaviour. Even the fake vault stores the *test behaviour*, not the PAN.

> **In production** the app sends card details **directly to the PSP's SDK** (client-side tokenization), so card data never touches your servers at all. Here the "PSP" runs inside payment-svc so that you can see and test the whole flow locally. That is the one deliberate shortcut.

### Declines are not failures

| Situation | What it is | How we model it | Counts toward the circuit breaker? |
|---|---|---|---|
| Insufficient funds, card declined, expired | A **business outcome**; the PSP is healthy | A normal return value: `AuthResult(approved=false, declineCode)` | **No** |
| 503, timeout, processing error | An **infrastructure failure** | A thrown `PspTransientException` → retried | **Yes** |

If declines were exceptions, ten customers with empty accounts would open the breaker and block every customer. This is the same lesson as the rider claim (`{claimed:false}`) in file 08.

### Retry vs circuit breaker: two different tools

| | Retry | Circuit breaker |
|---|---|---|
| Handles | **Transient** blips (one dropped packet, one 503) | **Sustained** outages |
| Behaviour | Try again after a short, jittered backoff | Stop calling entirely for a while |
| Danger if misused | **Retry storms**: 1,000 clients × 3 retries = 3,000 extra requests on a dying service | Opening too eagerly on a healthy but slow service |

**Layering:** the retry lives *inside* the breaker. One breaker "call" = up to 3 attempts, so the breaker sees a failure only when all retries fail. **Jitter** prevents synchronized retry spikes.

### Idempotency at three levels

1. **Event level:** `processed_events` table. The same Kafka message is never applied twice.
2. **Business level:** `unique(order_id)` on payments. There is never a second payment for one order.
3. **PSP level:** `orderId` is sent as the PSP's idempotency key. The fake PSP remembers results per key, so a retried authorization returns the same answer instead of charging twice (real PSPs like Stripe and Razorpay do exactly this).

### Consistency choice (CAP)

Payments choose **consistency over availability**. If the PSP is down, we do **not** optimistically mark the order paid; we fail it and cancel the order (compensation). Losing a sale is recoverable; double-charging or not charging is not.

---

## Test cards

Use any future expiry (e.g. `12/2030`), any 3-digit CVC (**4 digits for Amex**), and any name. All numbers pass the Luhn check.

| Card number | Brand | Outcome | Final order status | What it teaches |
|---|---|---|---|---|
| `4242 4242 4242 4242` | Visa | Approved | DELIVERED (payment CAPTURED) | Happy path |
| `5555 5555 5555 4444` | Mastercard | Approved | DELIVERED | Brand detection |
| `3782 822463 10005` | Amex | Approved (CVC must be 4 digits) | DELIVERED | Per-brand validation |
| `4000 0000 0000 0002` | Visa | Declined: `card_declined` | CANCELLED | Compensation; the breaker stays CLOSED |
| `4000 0000 0000 9995` | Visa | Declined: `insufficient_funds` | CANCELLED | Decline reason shown to the user |
| `4000 0000 0000 0069` | Visa | Declined: `expired_card` | CANCELLED | Issuer-side decline despite a valid date |
| `4000 0000 0000 0127` | Visa | Declined: `incorrect_cvc` | CANCELLED | |
| `4000 0000 0000 0119` | Visa | Transient `processing_error` on every attempt | CANCELLED (`PSP_UNAVAILABLE`) after 3 attempts | Retry with backoff, then breaker counting |
| `4000 0000 0000 1976` | Visa | Approved after 2.5 s | DELIVERED | Slow-call detection (`slow-call-duration-threshold: 2s`) |
| `4000 0000 0000 0341` | Visa | Authorized, but **capture** fails | DELIVERED; payment stays AUTHORIZED; event in `orders.events.dlt` | Capture retries → dead-letter topic |
| any other Luhn-valid number | detected | Approved | DELIVERED | |
| `4242 4242 4242 4241` | – | **Rejected at tokenization** (Luhn) | – | Input validation → `400` |

**Pre-seeded cards** (tied to the fixed user ids from file 03):

| User | Cards (first = default) |
|---|---|
| alice | 4242 (default), 0002, 9995, 0341 |
| admin | 5555 (default), Amex 0005 |
| bob, carol | none: riders don't pay |

---

## Step 1: Build, config, schema

`services/payment-svc/build.gradle.kts`

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
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("com.h2database:h2")              // FakePspTest (file 11): in-memory vault table
}
```

`src/main/resources/application.yml`

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
  application:
    name: payment-svc
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/payments
    username: quickbite
    password: quickbite
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  kafka:
    consumer:
      group-id: payment-svc
  cloud:
    circuitbreaker:
      resilience4j:
        disable-thread-pool: true

server:
  port: 8083

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,db

quickbite:
  outbox:
    enabled: true
  psp:
    latency-ms: 150
    failure-rate: 0.0      # global infrastructure fault injection (admin endpoint changes it at runtime)
  seed:
    cards: ${SEED_CARDS:true}          # pre-load test cards for alice/admin; false in production
  test-cards:
    enabled: ${TEST_CARDS_ENABLED:true} # exposes GET /api/payments/test-cards for the app's debug quick-fill

resilience4j:
  circuitbreaker:
    instances:
      psp:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 60
        wait-duration-in-open-state: 20s
        permitted-number-of-calls-in-half-open-state: 3
```

`src/main/resources/db/migration/V1__init.sql`

```sql
create table payments (
  id              uuid primary key,
  order_id        varchar(32) not null unique,     -- business-level idempotency
  customer_id     varchar(64) not null,
  amount          numeric(12,2) not null,
  currency        varchar(3) not null,
  status          varchar(20) not null,            -- AUTHORIZED | FAILED | CAPTURED
  psp_reference   varchar(100),
  failure_reason  varchar(200),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create table outbox (
  id uuid primary key, topic varchar(200) not null, msg_key varchar(200) not null,
  event_type varchar(100) not null, payload text not null,
  session_id varchar(100), correlation_id varchar(100),
  created_at timestamptz not null default now(), published_at timestamptz
);
create index idx_outbox_unpublished on outbox(created_at) where published_at is null;

create table processed_events (event_id varchar(64) primary key, processed_at timestamptz not null default now());
```

`src/main/resources/db/migration/V2__cards.sql`: a **new** migration, so an existing database upgrades cleanly. Never edit an applied migration: Flyway checksums would fail.

```sql
-- OUR side: only tokens + display data. No PAN, no CVC.
create table payment_methods (
  id           varchar(40) primary key,             -- "pm_..." (what clients and order-svc see)
  customer_id  varchar(64) not null,
  psp_token    varchar(64) not null,                -- "tok_..." (meaningful only to the PSP)
  brand        varchar(20) not null,
  last4        varchar(4)  not null,
  exp_month    int not null,
  exp_year     int not null,
  holder_name  varchar(100),
  is_default   boolean not null default false,
  created_at   timestamptz not null default now()
);
create index idx_pm_customer on payment_methods(customer_id);
-- The DATABASE enforces "at most one default card per customer" (a partial unique index):
create unique index ux_pm_one_default on payment_methods(customer_id) where is_default;

-- Snapshot of the card used, so receipts survive card deletion
alter table payments add column payment_method_id varchar(40);
alter table payments add column card_brand varchar(20);
alter table payments add column card_last4 varchar(4);

-- Stand-in for the PSP's own systems. Stores the TEST BEHAVIOUR of a token, never the card number.
create table psp_fake_vault (
  token        varchar(64) primary key,
  brand        varchar(20) not null,
  last4        varchar(4)  not null,
  behavior     varchar(30) not null,                -- APPROVE | DECLINE | PROCESSING_ERROR | SLOW | CAPTURE_FAIL
  decline_code varchar(40),
  created_at   timestamptz not null default now()
);
```

---

## Step 2: Domain

All Java lives under `services/payment-svc/src/main/java/com/quickbite/payment/`.

`PaymentApplication.java`

```java
package com.quickbite.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;

@SpringBootApplication
@EnableResilientMethods   // Spring Framework 7: activates @Retryable / @ConcurrencyLimit
public class PaymentApplication {
    public static void main(String[] args) { SpringApplication.run(PaymentApplication.class, args); }
}
```

`domain/PaymentMethod.java`

```java
package com.quickbite.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_methods")
public class PaymentMethod {
    @Id private String id;
    private String customerId;
    private String pspToken;
    private String brand;
    private String last4;
    private int expMonth;
    private int expYear;
    private String holderName;
    private boolean isDefault;
    private Instant createdAt;

    protected PaymentMethod() {}

    public static PaymentMethod create(String customerId, String pspToken, String brand, String last4,
                                       int expMonth, int expYear, String holderName, boolean isDefault) {
        var pm = new PaymentMethod();
        pm.id = "pm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        pm.customerId = customerId; pm.pspToken = pspToken; pm.brand = brand; pm.last4 = last4;
        pm.expMonth = expMonth; pm.expYear = expYear; pm.holderName = holderName;
        pm.isDefault = isDefault; pm.createdAt = Instant.now();
        return pm;
    }

    public void markDefault() { isDefault = true; }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getPspToken() { return pspToken; }
    public String getBrand() { return brand; }
    public String getLast4() { return last4; }
    public int getExpMonth() { return expMonth; }
    public int getExpYear() { return expYear; }
    public String getHolderName() { return holderName; }
    public boolean isDefault() { return isDefault; }
}
```

`domain/PaymentMethodRepository.java`

```java
package com.quickbite.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {
    List<PaymentMethod> findByCustomerIdOrderByCreatedAtAsc(String customerId);
    Optional<PaymentMethod> findByIdAndCustomerId(String id, String customerId);   // ownership built into the query
    Optional<PaymentMethod> findFirstByCustomerIdAndIsDefaultTrue(String customerId);
    long countByCustomerId(String customerId);

    /** Bulk update runs immediately, BEFORE we set the new default -> the partial unique index is never violated. */
    @Modifying
    @Query("update PaymentMethod p set p.isDefault = false where p.customerId = :customerId")
    void clearDefault(@Param("customerId") String customerId);
}
```

`domain/Payment.java`

```java
package com.quickbite.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    public enum Status { AUTHORIZED, FAILED, CAPTURED }

    @Id private UUID id;
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    @Enumerated(EnumType.STRING) private Status status;
    private String pspReference;
    private String failureReason;
    private String paymentMethodId;
    private String cardBrand;
    private String cardLast4;
    private Instant createdAt;
    private Instant updatedAt;

    protected Payment() {}

    public static Payment authorized(String orderId, String customerId, BigDecimal amount, String currency,
                                     PaymentMethod pm, String pspRef) {
        return create(orderId, customerId, amount, currency, Status.AUTHORIZED, pm, pspRef, null);
    }

    /** pm may be null (e.g. NO_PAYMENT_METHOD). */
    public static Payment failed(String orderId, String customerId, BigDecimal amount, String currency,
                                 PaymentMethod pm, String reason) {
        return create(orderId, customerId, amount, currency, Status.FAILED, pm, null, reason);
    }

    private static Payment create(String orderId, String customerId, BigDecimal amount, String currency,
                                  Status status, PaymentMethod pm, String ref, String reason) {
        var p = new Payment();
        p.id = UUID.randomUUID(); p.orderId = orderId; p.customerId = customerId; p.amount = amount;
        p.currency = currency; p.status = status; p.pspReference = ref; p.failureReason = reason;
        if (pm != null) { p.paymentMethodId = pm.getId(); p.cardBrand = pm.getBrand(); p.cardLast4 = pm.getLast4(); }
        p.createdAt = p.updatedAt = Instant.now();
        return p;
    }

    public void capture() {
        if (status != Status.AUTHORIZED) throw new IllegalStateException("cannot capture a " + status + " payment");
        status = Status.CAPTURED; updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getPspReference() { return pspReference; }
    public String getFailureReason() { return failureReason; }
    public String getCardBrand() { return cardBrand; }
    public String getCardLast4() { return cardLast4; }
}
```

`domain/PaymentRepository.java`

```java
package com.quickbite.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByOrderId(String orderId);
}
```

`domain/Events.java`

```java
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
```

---

## Step 3: The fake PSP (tokenization, test cards, fault injection, retry)

`psp/CardRules.java`: pure functions, which makes them easy to unit test (file 11).

```java
package com.quickbite.payment.psp;

public final class CardRules {
    private CardRules() {}

    public static String normalize(String raw) { return raw == null ? "" : raw.replaceAll("[\\s-]", ""); }

    /** Luhn mod-10: catches typos and every single-digit error, and most transpositions. */
    public static boolean luhnValid(String pan) {
        if (!pan.matches("\\d{13,19}")) return false;
        int sum = 0;
        for (int i = 0; i < pan.length(); i++) {
            int d = pan.charAt(pan.length() - 1 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        return sum % 10 == 0;
    }

    public static String brand(String pan) {
        if (pan.startsWith("4")) return "VISA";
        if (pan.matches("^(5[1-5]|2(2[2-9]|[3-6]\\d|7[01]|720)).*")) return "MASTERCARD";
        if (pan.matches("^3[47].*")) return "AMEX";
        if (pan.matches("^(60|65|81|82|508).*")) return "RUPAY";
        return "UNKNOWN";
    }

    public static String mask(String pan) { return pan.length() < 4 ? "****" : "****" + pan.substring(pan.length() - 4); }
}
```

`psp/FakePsp.java`

```java
package com.quickbite.payment.psp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;

/**
 * Simulates an external payment provider (think Stripe/Razorpay/Adyen) in-process.
 * Everything in this class and the psp_fake_vault table represents the PROVIDER'S side.
 */
@Component
public class FakePsp {
    private static final Logger log = LoggerFactory.getLogger(FakePsp.class);

    public enum Behavior { APPROVE, DECLINE, PROCESSING_ERROR, SLOW, CAPTURE_FAIL }
    public record TestCard(String number, String brand, Behavior behavior, String declineCode, String description) {}
    public record Config(long latencyMs, double failureRate) {}
    public record CardToken(String token, String brand, String last4) {}
    public record AuthResult(boolean approved, String reference, String declineCode) {}
    public static class PspTransientException extends RuntimeException {
        public PspTransientException(String m) { super(m); }
    }
    private record Vaulted(String brand, String last4, Behavior behavior, String declineCode) {}

    public static final List<TestCard> TEST_CARDS = List.of(
        new TestCard("4242424242424242", "VISA", Behavior.APPROVE, null, "Approved"),
        new TestCard("5555555555554444", "MASTERCARD", Behavior.APPROVE, null, "Approved"),
        new TestCard("378282246310005", "AMEX", Behavior.APPROVE, null, "Approved (4-digit CVC)"),
        new TestCard("4000000000000002", "VISA", Behavior.DECLINE, "card_declined", "Declined"),
        new TestCard("4000000000009995", "VISA", Behavior.DECLINE, "insufficient_funds", "Declined: insufficient funds"),
        new TestCard("4000000000000069", "VISA", Behavior.DECLINE, "expired_card", "Declined: expired card"),
        new TestCard("4000000000000127", "VISA", Behavior.DECLINE, "incorrect_cvc", "Declined: incorrect CVC"),
        new TestCard("4000000000000119", "VISA", Behavior.PROCESSING_ERROR, null, "Transient processing error (every attempt)"),
        new TestCard("4000000000001976", "VISA", Behavior.SLOW, null, "Approved after 2.5 s"),
        new TestCard("4000000000000341", "VISA", Behavior.CAPTURE_FAIL, null, "Authorizes, capture fails"));

    private static final Map<String, TestCard> BY_NUMBER = TEST_CARDS.stream()
            .collect(java.util.stream.Collectors.toMap(TestCard::number, c -> c));

    private final JdbcClient jdbc;
    private final AtomicReference<Config> config;
    // Provider-side idempotency and capture memory (a real PSP persists these; in-memory is fine for a fake)
    private final Map<String, AuthResult> authByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Behavior> behaviorByReference = new ConcurrentHashMap<>();

    public FakePsp(JdbcClient jdbc, @Value("${quickbite.psp.latency-ms}") long latency,
                   @Value("${quickbite.psp.failure-rate}") double rate) {
        this.jdbc = jdbc;
        this.config = new AtomicReference<>(new Config(latency, rate));
    }

    /** Validates the card and returns an opaque token. The PAN and CVC are NOT stored and NOT logged. */
    public CardToken tokenize(String rawNumber, int expMonth, int expYear, String cvc) {
        String pan = CardRules.normalize(rawNumber);
        if (!CardRules.luhnValid(pan)) throw new IllegalArgumentException("Invalid card number");
        try {
            if (YearMonth.of(expYear, expMonth).isBefore(YearMonth.now())) throw new IllegalArgumentException("Card has expired");
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid expiry date");
        }
        String brand = CardRules.brand(pan);
        int cvcLength = "AMEX".equals(brand) ? 4 : 3;
        if (cvc == null || !cvc.matches("\\d{" + cvcLength + "}")) {
            throw new IllegalArgumentException("CVC must be " + cvcLength + " digits");
        }
        TestCard tc = BY_NUMBER.get(pan);
        Behavior behavior = tc == null ? Behavior.APPROVE : tc.behavior();
        String last4 = pan.substring(pan.length() - 4);
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("insert into psp_fake_vault(token, brand, last4, behavior, decline_code) values (?,?,?,?,?)")
            .params(token, brand, last4, behavior.name(), tc == null ? null : tc.declineCode()).update();
        log.info("PSP tokenized {} {} -> behaviour {}", brand, CardRules.mask(pan), behavior);
        return new CardToken(token, brand, last4);
    }

    /**
     * Declines are RETURNED (business outcome). Only infrastructure problems THROW (and are retried).
     * idempotencyKey = orderId: a retried call returns the stored result instead of charging twice.
     */
    @Retryable(includes = PspTransientException.class, maxRetries = 2, delay = 100, multiplier = 2, jitter = 50)
    public AuthResult authorize(String idempotencyKey, String token, BigDecimal amount) {
        AuthResult previous = authByIdempotencyKey.get(idempotencyKey);
        if (previous != null) return previous;

        Vaulted card = vault(token);
        simulateInfrastructure("authorize " + idempotencyKey);
        switch (card.behavior()) {
            case PROCESSING_ERROR -> {
                log.warn("PSP processing_error for {} ****{}", card.brand(), card.last4());
                throw new PspTransientException("processing_error");
            }
            case SLOW -> sleep(2_500);
            default -> { }
        }
        AuthResult result = card.behavior() == Behavior.DECLINE
                ? new AuthResult(false, null, card.declineCode())
                : new AuthResult(true, "psp_" + UUID.randomUUID(), null);
        authByIdempotencyKey.put(idempotencyKey, result);
        if (result.approved()) behaviorByReference.put(result.reference(), card.behavior());
        log.info("PSP authorize {} {} ****{}: {}", idempotencyKey, card.brand(), card.last4(),
                result.approved() ? "APPROVED" : "DECLINED " + result.declineCode());
        return result;
    }

    @Retryable(includes = PspTransientException.class, maxRetries = 2, delay = 100, multiplier = 2, jitter = 50)
    public void capture(String reference) {
        simulateInfrastructure("capture " + reference);
        if (behaviorByReference.get(reference) == Behavior.CAPTURE_FAIL) {
            throw new PspTransientException("capture failed for " + reference);
        }
    }

    private Vaulted vault(String token) {
        return jdbc.sql("select brand, last4, behavior, decline_code from psp_fake_vault where token = ?")
                .param(token)
                .query((rs, n) -> new Vaulted(rs.getString(1), rs.getString(2),
                        Behavior.valueOf(rs.getString(3)), rs.getString(4)))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown card token"));
    }

    /** Global fault injection, independent of the card: latency + random 503s. */
    private void simulateInfrastructure(String op) {
        Config c = config.get();
        sleep(c.latencyMs());
        if (ThreadLocalRandom.current().nextDouble() < c.failureRate()) {
            log.warn("PSP transient failure on {}", op);
            throw new PspTransientException("PSP returned 503 for " + op);
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public Config getConfig() { return config.get(); }
    public void setConfig(Config c) { config.set(c); }
}
```

> Check `@Retryable`'s attribute names in your Spring Framework 7 patch; they evolved during the milestones.

---

## Step 4: Services

`app/PaymentMethodService.java`

```java
package com.quickbite.payment.app;

import com.quickbite.payment.domain.PaymentMethod;
import com.quickbite.payment.domain.PaymentMethodRepository;
import com.quickbite.payment.psp.FakePsp;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class PaymentMethodService {
    public static final int MAX_CARDS = 10;

    public record AddCard(@NotBlank @Size(max = 23) String number,
                          @Min(1) @Max(12) int expMonth,
                          @Min(2000) @Max(2100) int expYear,
                          @NotBlank @Size(min = 3, max = 4) String cvc,
                          @Size(max = 100) String holderName) {
        @Override public String toString() { return "AddCard[****, " + expMonth + "/" + expYear + "]"; } // never log card data
    }

    private final PaymentMethodRepository repo;
    private final FakePsp psp;
    private final TransactionTemplate tx;

    public PaymentMethodService(PaymentMethodRepository repo, FakePsp psp, TransactionTemplate tx) {
        this.repo = repo; this.psp = psp; this.tx = tx;
    }

    public PaymentMethod add(String customerId, AddCard c) {
        if (repo.countByCustomerId(customerId) >= MAX_CARDS) throw new IllegalStateException("Maximum " + MAX_CARDS + " cards");
        FakePsp.CardToken t = psp.tokenize(c.number(), c.expMonth(), c.expYear(), c.cvc());  // card data goes ONLY here
        return tx.execute(s -> {
            boolean first = repo.countByCustomerId(customerId) == 0;          // first card becomes the default
            return repo.save(PaymentMethod.create(customerId, t.token(), t.brand(), t.last4(),
                    c.expMonth(), c.expYear(), c.holderName(), first));
        });
    }

    public List<PaymentMethod> list(String customerId) { return repo.findByCustomerIdOrderByCreatedAtAsc(customerId); }

    public void setDefault(String customerId, String id) {
        tx.executeWithoutResult(s -> {
            PaymentMethod pm = repo.findByIdAndCustomerId(id, customerId).orElseThrow(() -> new NoSuchElementException("card not found"));
            repo.clearDefault(customerId);
            pm.markDefault();
        });
    }

    public void delete(String customerId, String id) {
        tx.executeWithoutResult(s -> {
            PaymentMethod pm = repo.findByIdAndCustomerId(id, customerId).orElseThrow(() -> new NoSuchElementException("card not found"));
            repo.delete(pm);
            repo.flush();
            if (pm.isDefault()) {                                           // promote the oldest remaining card
                repo.findByCustomerIdOrderByCreatedAtAsc(customerId).stream().findFirst().ifPresent(PaymentMethod::markDefault);
            }
        });
    }

    /** null id -> default card. Ownership is part of the lookup: another customer's pm_ id resolves to empty. */
    public Optional<PaymentMethod> resolve(String customerId, String paymentMethodId) {
        return paymentMethodId == null
                ? repo.findFirstByCustomerIdAndIsDefaultTrue(customerId)
                : repo.findByIdAndCustomerId(paymentMethodId, customerId);
    }
}
```

`app/PaymentService.java`

```java
package com.quickbite.payment.app;

import com.quickbite.common.outbox.IdempotencyGuard;
import com.quickbite.common.outbox.OutboxWriter;
import com.quickbite.payment.domain.*;
import com.quickbite.payment.domain.Events.*;
import com.quickbite.payment.psp.FakePsp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private record Outcome(boolean approved, String reference, String reason) {}

    private final PaymentRepository repo;
    private final PaymentMethodService methods;
    private final FakePsp psp;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final OutboxWriter outbox;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository repo, PaymentMethodService methods, FakePsp psp,
                          CircuitBreakerFactory<?, ?> breakers, OutboxWriter outbox,
                          IdempotencyGuard idempotency, TransactionTemplate tx) {
        this.repo = repo; this.methods = methods; this.psp = psp; this.breakers = breakers;
        this.outbox = outbox; this.idempotency = idempotency; this.tx = tx;
    }

    public void onOrderCreated(String eventId, OrderCreated e) {
        // Cheap early exit; the real guarantees are the unique constraint + processed_events below.
        if (repo.findByOrderId(e.orderId()).isPresent()) return;

        PaymentMethod pm = methods.resolve(e.customerId(), e.paymentMethodId()).orElse(null);
        Outcome outcome = pm == null ? new Outcome(false, null, "NO_PAYMENT_METHOD") : authorize(e, pm);

        tx.executeWithoutResult(s -> {
            if (!idempotency.firstTime(eventId)) return;
            Payment p = outcome.approved()
                    ? Payment.authorized(e.orderId(), e.customerId(), e.amount(), e.currency(), pm, outcome.reference())
                    : Payment.failed(e.orderId(), e.customerId(), e.amount(), e.currency(), pm, outcome.reason());
            repo.save(p);
            String type = outcome.approved() ? "payment.authorized" : "payment.failed";
            outbox.write(Events.PAYMENTS_TOPIC, e.orderId(), type,
                    new PaymentEvent(e.orderId(), p.getId().toString(), p.getFailureReason()));
            log.info("Order {} -> {} {}", e.orderId(), type, outcome.reason() == null ? "" : "(" + outcome.reason() + ")");
        });
    }

    /** PSP call OUTSIDE the transaction, wrapped: breaker( retry( psp ) ). */
    private Outcome authorize(OrderCreated e, PaymentMethod pm) {
        FakePsp.AuthResult r = breakers.create("psp").run(
                () -> psp.authorize(e.orderId(), pm.getPspToken(), e.amount()),
                // t is PspTransientException (retries exhausted) or CallNotPermittedException (breaker OPEN)
                t -> { log.warn("PSP unavailable for order {}: {}", e.orderId(), t.toString()); return null; });
        if (r == null) return new Outcome(false, null, "PSP_UNAVAILABLE");
        return r.approved() ? new Outcome(true, r.reference(), null)
                            : new Outcome(false, null, "DECLINED: " + r.declineCode());
    }

    public void onOrderStatusChanged(String eventId, OrderStatusChanged e) {
        if (!"DELIVERED".equals(e.status())) return;
        var payment = repo.findByOrderId(e.orderId()).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.AUTHORIZED) return;

        // Throws if capture keeps failing -> Kafka retries (3x) -> orders.events.dlt. We must NOT give up on money owed.
        breakers.create("psp").run(() -> { psp.capture(payment.getPspReference()); return null; });

        tx.executeWithoutResult(s -> {
            if (!idempotency.firstTime(eventId)) return;
            Payment p = repo.findById(payment.getId()).orElseThrow();
            p.capture();
            outbox.write(Events.PAYMENTS_TOPIC, e.orderId(), "payment.captured",
                    new PaymentEvent(e.orderId(), p.getId().toString(), null));
        });
    }
}
```

**Two failure policies, on purpose:**

- **Authorize:** failure is *converted into a business outcome* (`payment.failed` with a reason). The customer gets a fast, clear answer, and the order is cancelled by compensation.
- **Capture:** failure *throws*, so it goes Kafka retries → `orders.events.dlt`. The food was delivered, so we must not silently drop the charge. A DLT entry is a to-do for an operator or a reprocessing job.

---

## Step 5: Seed test cards

`config/CardSeeder.java`

```java
package com.quickbite.payment.config;

import com.quickbite.payment.app.PaymentMethodService;
import com.quickbite.payment.app.PaymentMethodService.AddCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User ids are fixed in the Keycloak realm import (file 03), so they match the token's "sub". */
@Component
@ConditionalOnProperty(name = "quickbite.seed.cards", havingValue = "true")
public class CardSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CardSeeder.class);
    static final Map<String, List<String>> CARDS = new LinkedHashMap<>();
    static {
        CARDS.put("11111111-1111-1111-1111-111111111111",               // alice (first card = default)
                List.of("4242424242424242", "4000000000000002", "4000000000009995", "4000000000000341"));
        CARDS.put("44444444-4444-4444-4444-444444444444",               // admin
                List.of("5555555555554444", "378282246310005"));
    }

    private final PaymentMethodService methods;
    public CardSeeder(PaymentMethodService methods) { this.methods = methods; }

    @Override
    public void run(String... args) {
        CARDS.forEach((owner, numbers) -> {
            if (!methods.list(owner).isEmpty()) return;               // idempotent: seed once
            for (String n : numbers) {
                String cvc = n.startsWith("34") || n.startsWith("37") ? "1234" : "123";
                methods.add(owner, new AddCard(n, 12, 2030, cvc, "Test Card"));
            }
            log.info("Seeded {} test cards for {}", numbers.size(), owner);
        });
    }
}
```

---

## Step 6: Listener, topics, API

`messaging/OrderEventsListener.java`

```java
package com.quickbite.payment.messaging;

import com.quickbite.common.context.Headers;
import com.quickbite.payment.app.PaymentService;
import com.quickbite.payment.domain.Events;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration.header;

@Component
public class OrderEventsListener {
    private final PaymentService payments;
    private final JsonMapper json;

    public OrderEventsListener(PaymentService payments, JsonMapper json) { this.payments = payments; this.json = json; }

    @KafkaListener(topics = Events.ORDERS_TOPIC)
    public void on(ConsumerRecord<String, String> rec) {
        String eventId = header(rec, Headers.K_EVENT_ID);
        switch (header(rec, Headers.K_EVENT_TYPE)) {
            case "order.created" -> payments.onOrderCreated(eventId, json.readValue(rec.value(), Events.OrderCreated.class));
            case "order.status-changed" -> payments.onOrderStatusChanged(eventId, json.readValue(rec.value(), Events.OrderStatusChanged.class));
            default -> { /* ignore unknown types: forward compatibility */ }
        }
    }
}
```

`messaging/TopicConfig.java`

```java
package com.quickbite.payment.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class TopicConfig {
    @Bean
    KafkaAdmin.NewTopics paymentTopics(@Value("${KAFKA_PARTITIONS:3}") int p, @Value("${KAFKA_REPLICAS:1}") short r) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("orders.events").partitions(p).replicas(r).build(),
                TopicBuilder.name("orders.events.dlt").partitions(p).replicas(r).build(),
                TopicBuilder.name("payments.events").partitions(p).replicas(r).build());
    }
}
```

`api/PaymentMethodController.java`

```java
package com.quickbite.payment.api;

import com.quickbite.payment.app.PaymentMethodService;
import com.quickbite.payment.app.PaymentMethodService.AddCard;
import com.quickbite.payment.domain.PaymentMethod;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments/methods")
@PreAuthorize("hasRole('customer')")
public class PaymentMethodController {
    /** What clients see: never the token, never the PAN. */
    public record PaymentMethodView(String id, String brand, String last4, int expMonth, int expYear,
                                    String holderName, boolean isDefault) {
        static PaymentMethodView from(PaymentMethod pm) {
            return new PaymentMethodView(pm.getId(), pm.getBrand(), pm.getLast4(), pm.getExpMonth(), pm.getExpYear(),
                    pm.getHolderName(), pm.isDefault());
        }
    }

    private final PaymentMethodService methods;
    public PaymentMethodController(PaymentMethodService methods) { this.methods = methods; }

    @GetMapping
    public List<PaymentMethodView> list(@AuthenticationPrincipal Jwt jwt) {
        return methods.list(jwt.getSubject()).stream().map(PaymentMethodView::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentMethodView add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddCard card) {
        return PaymentMethodView.from(methods.add(jwt.getSubject(), card));
    }

    @PostMapping("/{id}/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void makeDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { methods.setDefault(jwt.getSubject(), id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { methods.delete(jwt.getSubject(), id); }
}
```

`api/PaymentController.java`

```java
package com.quickbite.payment.api;

import com.quickbite.payment.domain.Payment;
import com.quickbite.payment.domain.PaymentRepository;
import com.quickbite.payment.psp.FakePsp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    public record PaymentView(String orderId, String status, BigDecimal amount, String currency,
                              String cardBrand, String cardLast4, String failureReason) {}

    private final PaymentRepository repo;
    private final FakePsp psp;
    private final Resilience4JCircuitBreakerFactory breakers;
    private final boolean testCardsEnabled;

    public PaymentController(PaymentRepository repo, FakePsp psp, Resilience4JCircuitBreakerFactory breakers,
                             @Value("${quickbite.test-cards.enabled:false}") boolean testCardsEnabled) {
        this.repo = repo; this.psp = psp; this.breakers = breakers; this.testCardsEnabled = testCardsEnabled;
    }

    @GetMapping("/orders/{orderId}")
    public PaymentView byOrder(@PathVariable String orderId, @AuthenticationPrincipal Jwt jwt) {
        Payment p = repo.findByOrderId(orderId).orElseThrow(() -> new NoSuchElementException("no payment for " + orderId));
        if (!p.getCustomerId().equals(jwt.getSubject())) throw new AccessDeniedException("not your payment");
        return new PaymentView(p.getOrderId(), p.getStatus().name(), p.getAmount(), p.getCurrency(),
                p.getCardBrand(), p.getCardLast4(), p.getFailureReason());
    }

    /** Dev/test only: the catalogue of magic numbers, used by the Android debug quick-fill. */
    @GetMapping("/test-cards")
    public List<FakePsp.TestCard> testCards() {
        if (!testCardsEnabled) throw new NoSuchElementException("not available");
        return FakePsp.TEST_CARDS;
    }

    /** Chaos control: e.g. {"latencyMs": 3000, "failureRate": 1.0} */
    @PostMapping("/admin/psp")
    @PreAuthorize("hasRole('admin')")
    public FakePsp.Config setPsp(@RequestBody FakePsp.Config c) { psp.setConfig(c); return c; }

    @GetMapping("/admin/psp")
    @PreAuthorize("hasRole('admin')")
    public Map<String, Object> pspState() {
        var cb = breakers.getCircuitBreakerRegistry().circuitBreaker("psp");
        return Map.of("config", psp.getConfig(),
                "breakerState", cb.getState().name(),
                "failureRate", cb.getMetrics().getFailureRate(),
                "slowCallRate", cb.getMetrics().getSlowCallRate(),
                "bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
    }
}
```

Helper script `scripts/add-card.sh`: used by later files and handy for manual tests.

```bash
#!/usr/bin/env bash
# usage: scripts/add-card.sh <user> <password> <card-number>   -> prints the new pm_ id
set -euo pipefail
T=$(scripts/token.sh "$1" "$2")
N=$(echo "$3" | tr -d ' -')
CVC=123; [[ "$N" =~ ^3[47] ]] && CVC=1234
curl -s -X POST localhost:8000/api/payments/methods -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d "{\"number\":\"$N\",\"expMonth\":12,\"expYear\":2030,\"cvc\":\"$CVC\",\"holderName\":\"$1\"}" | jq -r '.id // .detail'
```

---

## Step 7: Run and verify

```bash
./gradlew :services:payment-svc:bootRun
chmod +x scripts/add-card.sh
```

You now have the gateway, catalog, order and payment services running.

```bash
ALICE=$(scripts/token.sh alice alice); ADMIN=$(scripts/token.sh admin admin)

# Alice's seeded cards
curl -s -H "Authorization: Bearer $ALICE" localhost:8000/api/payments/methods | jq -c '.[] | {id, brand, last4, isDefault}'
pm() { curl -s -H "Authorization: Bearer $ALICE" localhost:8000/api/payments/methods | jq -r --arg l "$1" '.[] | select(.last4==$l) | .id'; }

# place <pmId|""> : order Masala Dosa at Dosa Junction with a given card ("" = default card)
place() {
  local pmj; [ -n "$1" ] && pmj=",\"paymentMethodId\":\"$1\"" || pmj=""
  curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
    -d "{\"restaurantId\":\"r1\",\"items\":[{\"menuItemId\":\"r1-i1\",\"quantity\":1}],\"deliveryLat\":12.93,\"deliveryLon\":77.62$pmj}" | jq -r .id; }
status()  { curl -s -H "Authorization: Bearer $ALICE" localhost:8000/api/orders/$1 | jq -r .status; }
payment() { curl -s -H "Authorization: Bearer $ALICE" localhost:8000/api/payments/orders/$1 | jq -c '{status, card: (.cardBrand + " " + .cardLast4), failureReason}'; }
```

### A) Cards: validation and tokenization

```bash
scripts/add-card.sh alice alice "4242 4242 4242 4241"   # Invalid card number   (Luhn)
scripts/add-card.sh alice alice 378282246310005          # pm_... (Amex, CVC 1234 used by the script)
T=$(scripts/token.sh alice alice)
curl -s -X POST localhost:8000/api/payments/methods -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"number":"4242424242424242","expMonth":1,"expYear":2020,"cvc":"123"}' | jq -r .detail          # Card has expired

# The database never saw the card number:
docker exec -it quickbite-postgres-1 psql -U quickbite -d payments -c "select id, brand, last4, is_default from payment_methods;"
docker exec -it quickbite-postgres-1 psql -U quickbite -d payments -c "select token, last4, behavior from psp_fake_vault;"
```

### B) Every test card through the saga

```bash
for L in 4242 0002 9995; do O=$(place "$(pm $L)"); sleep 3; echo "$L -> $(status $O) $(payment $O)"; done
O=$(place ""); sleep 3; echo "default -> $(status $O) $(payment $O)"            # uses the default card (4242)

# Cards alice doesn't have yet:
for N in 4000000000000069 4000000000000127 4000000000000119 4000000000001976; do
  P=$(scripts/add-card.sh alice alice $N); O=$(place $P); sleep 5; echo "${N: -4} -> $(status $O) $(payment $O)"; done
```

| Card | Expected |
|---|---|
| 4242 / default | `PAID`, payment `AUTHORIZED`, card `VISA 4242` |
| 0002 | `CANCELLED`, `DECLINED: card_declined` |
| 9995 | `CANCELLED`, `DECLINED: insufficient_funds` |
| 0069 / 0127 | `CANCELLED`, `DECLINED: expired_card` / `incorrect_cvc` |
| 0119 | `CANCELLED`, `PSP_UNAVAILABLE`; the logs show 3 attempts (retry with backoff) |
| 1976 | `PAID` after ~2.5 s; `slowCallRate` rises in `/admin/psp` |

### C) Declines don't trip the breaker; infrastructure failures do

```bash
for i in $(seq 1 10); do place "$(pm 0002)" >/dev/null; done; sleep 5
curl -s -H "Authorization: Bearer $ADMIN" localhost:8000/api/payments/admin/psp | jq '{breakerState, failureRate}'   # CLOSED

P=$(pm 0119); [ -z "$P" ] && P=$(scripts/add-card.sh alice alice 4000000000000119)
for i in $(seq 1 6); do place $P >/dev/null; done; sleep 10
curl -s -H "Authorization: Bearer $ADMIN" localhost:8000/api/payments/admin/psp | jq '{breakerState, failureRate}'   # OPEN
O=$(place "$(pm 4242)"); sleep 2; echo "$(status $O) $(payment $O)"   # CANCELLED PSP_UNAVAILABLE: even good cards fail fast while OPEN
sleep 21; O=$(place "$(pm 4242)"); sleep 3; status $O                 # PAID again (half-open -> closed)
```

### D) Global fault injection (all cards)

```bash
curl -s -X POST localhost:8000/api/payments/admin/psp -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"latencyMs":150,"failureRate":0.3}'
for i in 1 2 3 4 5 6; do O=$(place ""); sleep 2; status $O; done     # mostly PAID: retries absorb 30% blips
curl -s -X POST localhost:8000/api/payments/admin/psp -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"latencyMs":150,"failureRate":0.0}'
```

### E) Ownership: another customer's card id is useless

```bash
ADMIN_PM=$(curl -s -H "Authorization: Bearer $ADMIN" localhost:8000/api/payments/methods | jq -r '.[0].id')
O=$(place $ADMIN_PM); sleep 3; payment $O          # failureReason: NO_PAYMENT_METHOD (alice can't pay with admin's card)
```

### F) Idempotency: replay every event

```bash
# Stop payment-svc (the group must be inactive), reset its offsets, start it again
docker exec -it quickbite-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group payment-svc --topic orders.events --reset-offsets --to-earliest --execute
docker exec -it quickbite-postgres-1 psql -U quickbite -d payments -c \
  "select count(*) total, count(distinct order_id) distinct_orders from payments;"    # total == distinct_orders
```

### G) Dead letters: poison messages and failed captures

```bash
# Poison message
docker exec -i quickbite-kafka-1 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic orders.events --property parse.headers=true --property headers.delimiter=\| \
  <<< 'eventType:order.created,eventId:poison-1|{this is not json'

# Capture failure: order with the 0341 card, then deliver it (needs file 08's rider flow; revisit after file 08)
#   -> order DELIVERED, payment stays AUTHORIZED, the delivered event lands in orders.events.dlt
docker exec -it quickbite-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders.events.dlt --from-beginning --timeout-ms 5000 --property print.headers=true
```

| Check | Pass condition |
|---|---|
| A | Luhn/expiry/CVC errors return `400` with a clear message; no card numbers in any table |
| B | Each card produces the outcome in the table |
| C | 10 declines → breaker CLOSED; 6 × 0119 → OPEN; recovers after 20 s |
| D | Most orders PAID despite a 30% blip rate |
| E | `NO_PAYMENT_METHOD` |
| F | `total == distinct_orders` |
| G | Both messages visible in `orders.events.dlt` with `kafka_dlt-exception-*` headers |

**Checkpoint:**

1. Why must `insufficient_funds` be a return value and not an exception? Describe the outage that would follow otherwise.
2. The fake vault stores behaviour, not the PAN. Which component in a real deployment would hold the PAN, and what does your system hold instead?
3. Why is it safe for `onOrderCreated` to call the PSP *before* the `processed_events` check?
