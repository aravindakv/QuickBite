# 11 — Testing Guide: Unit → Integration → Contract → E2E → Load → Chaos → Security

**Goal:** a test suite that proves both the **functional** requirements (orders work) and the **non-functional** ones (it's fast, it survives failures, it's secure). Every test type below maps to a requirement from Phase 0.

---

## The test pyramid for this project

| Level | Share | Speed | What it proves | Tools |
|---|---|---|---|---|
| Unit | ~70% | ms | Business rules (state machine, pricing, ID generation) | JUnit 5, AssertJ, Mockito |
| Architecture | a few | ms | Layering rules can't silently rot | ArchUnit |
| Integration | ~20% | seconds | Real Postgres, Kafka, Redis: outbox, idempotency, SQL, security wiring | Testcontainers, Spring Boot test |
| Contract | a few | ms | Producer and consumer agree on event/API shapes | Shared JSON fixtures (→ Pact / Spring Cloud Contract) |
| End-to-end | ~10% | minutes | The whole flow through NGINX, gateway and every service | Bash + curl + jq, the mobile test plan (file 10) |
| Load | on demand | minutes | Latency/throughput NFRs, per-pod capacity | k6 |
| Chaos | on demand | minutes | Resilience NFRs | docker stop/pause, fault-injection endpoints |
| Security | on demand | minutes | OWASP API Top 10 basics | Scripted negative tests, OWASP ZAP |

**Rule:** the lower the level, the more tests you write. Integration tests catch what mocks hide (SQL typos, transaction boundaries, Kafka serialization). E2E tests are slow and flaky, so keep them few and meaningful.

---

## Step 1: Unit tests

Add AssertJ (it comes with `spring-boot-starter-test`). All paths below are under `services/order-svc/src/test/java/com/quickbite/order/`.

`domain/OrderTest.java`

```java
package com.quickbite.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OrderTest {
    private Order newOrder() {
        return Order.place(1L, "alice", "r1",
                List.of(new OrderLine("i1", "Dosa", 2, new BigDecimal("120.00")),
                        new OrderLine("i2", "Coffee", 1, new BigDecimal("40.50"))),
                12.9, 77.6, "key-1");
    }

    @Test
    void totalIsComputedFromServerPrices() {
        assertThat(newOrder().getTotalAmount()).isEqualByComparingTo("280.50");
    }

    @Test
    void emptyOrderRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(1L, "a", "r", List.of(), 0, 0, null));
    }

    @Test
    void happyPathTransitions() {
        Order o = newOrder();
        o.transitionTo(OrderStatus.PAID);
        o.assignRider("bob");
        o.transitionTo(OrderStatus.PICKED_UP);
        o.transitionTo(OrderStatus.DELIVERED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @ParameterizedTest
    @CsvSource({ "PENDING_PAYMENT,DELIVERED", "PENDING_PAYMENT,RIDER_ASSIGNED", "PAID,PICKED_UP" })
    void illegalTransitionsRejected(OrderStatus from, OrderStatus to) {
        Order o = newOrder();
        if (from == OrderStatus.PAID) o.transitionTo(OrderStatus.PAID);
        assertThatIllegalStateException().isThrownBy(() -> o.transitionTo(to));
    }

    @Test
    void terminalStatesAreFinal() {
        Order o = newOrder();
        o.transitionTo(OrderStatus.CANCELLED);
        for (OrderStatus s : OrderStatus.values()) assertThat(OrderStatus.CANCELLED.canMoveTo(s)).isFalse();
    }

    @Test
    void onlyTheAssignedRiderMayAct() {
        Order o = newOrder();
        o.transitionTo(OrderStatus.PAID);
        o.assignRider("bob");
        assertThatThrownBy(() -> o.requireRider("carol")).isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> o.requireRider("bob")).doesNotThrowAnyException();
    }
}
```

`libs/common/src/test/java/com/quickbite/common/id/SnowflakeIdGeneratorTest.java`

```java
package com.quickbite.common.id;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeIdGeneratorTest {
    @Test
    void uniqueAndIncreasingUnderConcurrency() throws Exception {
        var gen = new SnowflakeIdGenerator(7);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < 16; t++) pool.submit(() -> { for (int i = 0; i < 10_000; i++) ids.add(gen.nextId()); });
        }
        assertThat(ids).hasSize(160_000);                      // no duplicates

        long a = gen.nextId(), b = gen.nextId();
        assertThat(b).isGreaterThan(a);                         // time-sortable
        assertThat((a >> 12) & 0x3FF).isEqualTo(7);             // node id encoded in bits 12..21
    }
}
```

`libs/common/src/test/java/com/quickbite/common/health/LoopWatchdogTest.java`

```java
package com.quickbite.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoopWatchdogTest {
    @Test
    void goesDownWhenALoopStopsBeating() throws Exception {
        var wd = new LoopWatchdog(Duration.ofMillis(100));
        wd.beat("dispatcher");
        assertThat(wd.health().getStatus()).isEqualTo(Status.UP);
        Thread.sleep(150);
        assertThat(wd.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(wd.health().getDetails().get("stuckLoops")).asList().containsExactly("dispatcher");
        wd.beat("dispatcher");
        assertThat(wd.health().getStatus()).isEqualTo(Status.UP);   // recovers
    }
}
```

### Payment card rules and the fake PSP

`services/payment-svc/src/test/java/com/quickbite/payment/psp/CardRulesTest.java`

```java
package com.quickbite.payment.psp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CardRulesTest {
    @ParameterizedTest
    @ValueSource(strings = {"4242424242424242", "5555555555554444", "378282246310005", "4000000000000002",
                            "4000000000009995", "4000000000000119", "4000000000001976", "4000000000000341"})
    void allTestCardsPassLuhn(String pan) { assertThat(CardRules.luhnValid(pan)).isTrue(); }

    @ParameterizedTest
    @ValueSource(strings = {"4242424242424241", "1234", "abcd123412341234", ""})
    void invalidNumbersFailLuhn(String pan) { assertThat(CardRules.luhnValid(pan)).isFalse(); }

    @ParameterizedTest
    @CsvSource({"4242424242424242,VISA", "5555555555554444,MASTERCARD", "2223003122003222,MASTERCARD",
                "378282246310005,AMEX", "6521000000000000,RUPAY", "9999999999999995,UNKNOWN"})
    void detectsBrand(String pan, String brand) { assertThat(CardRules.brand(pan)).isEqualTo(brand); }

    @Test
    void normalizesAndMasks() {
        assertThat(CardRules.normalize("4242 4242-4242 4242")).isEqualTo("4242424242424242");
        assertThat(CardRules.mask("4242424242424242")).isEqualTo("****4242");   // the only form allowed in logs
    }
}
```

`services/payment-svc/src/test/java/com/quickbite/payment/psp/FakePspTest.java`: a plain unit test with an in-memory stand-in for the vault table.

```java
package com.quickbite.payment.psp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class FakePspTest {
    FakePsp psp;

    @BeforeEach
    void setUp() {
        // H2 in-memory DB with the same vault table (add testImplementation("com.h2database:h2"))
        var ds = new EmbeddedDatabaseBuilder().generateUniqueName(true).build();
        var jdbc = JdbcClient.create(ds);
        jdbc.sql("create table psp_fake_vault(token varchar(64) primary key, brand varchar(20), last4 varchar(4), " +
                 "behavior varchar(30), decline_code varchar(40), created_at timestamp default current_timestamp)").update();
        psp = new FakePsp(jdbc, 0, 0.0);
    }

    @Test void approvesGoodCard() {
        var t = psp.tokenize("4242 4242 4242 4242", 12, 2030, "123");
        assertThat(t.brand()).isEqualTo("VISA");
        assertThat(t.last4()).isEqualTo("4242");
        assertThat(psp.authorize("order-1", t.token(), BigDecimal.TEN).approved()).isTrue();
    }

    @Test void declinesAreReturnedNotThrown() {
        var t = psp.tokenize("4000000000009995", 12, 2030, "123");
        var r = psp.authorize("order-2", t.token(), BigDecimal.TEN);
        assertThat(r.approved()).isFalse();
        assertThat(r.declineCode()).isEqualTo("insufficient_funds");
    }

    @Test void processingErrorIsTransient() {
        var t = psp.tokenize("4000000000000119", 12, 2030, "123");
        // (no Spring proxy here -> no @Retryable; a single attempt throws)
        assertThatThrownBy(() -> psp.authorize("order-3", t.token(), BigDecimal.TEN))
            .isInstanceOf(FakePsp.PspTransientException.class);
    }

    @Test void sameIdempotencyKeySameResult() {
        var t = psp.tokenize("4242424242424242", 12, 2030, "123");
        var a = psp.authorize("order-4", t.token(), BigDecimal.TEN);
        var b = psp.authorize("order-4", t.token(), BigDecimal.TEN);
        assertThat(b.reference()).isEqualTo(a.reference());      // no double charge on retry
    }

    @Test void validationErrors() {
        assertThatIllegalArgumentException().isThrownBy(() -> psp.tokenize("4242424242424241", 12, 2030, "123"))
            .withMessageContaining("Invalid card number");
        assertThatIllegalArgumentException().isThrownBy(() -> psp.tokenize("4242424242424242", 1, 2020, "123"))
            .withMessageContaining("expired");
        assertThatIllegalArgumentException().isThrownBy(() -> psp.tokenize("378282246310005", 12, 2030, "123"))
            .withMessageContaining("4 digits");                   // Amex needs a 4-digit CVC
    }
}
```

---

## Step 2: Architecture tests (ArchUnit)

`services/order-svc/src/test/java/com/quickbite/order/ArchitectureTest.java`

```java
package com.quickbite.order;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

@AnalyzeClasses(packages = "com.quickbite.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The domain is the core: it must not know about HTTP, Kafka, or remote clients. */
    @ArchTest
    static final ArchRule domainIsIndependent = noClasses().that().resideInAPackage("..order.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..order.api..", "..order.app..", "..order.client..", "..order.messaging..",
                    "org.springframework.web..", "org.springframework.kafka..");

    /** Remote clients are infrastructure: they must not reach into the web layer. */
    @ArchTest
    static final ArchRule clientsDontUseApi = noClasses().that().resideInAPackage("..order.client..")
            .should().dependOnClassesThat().resideInAPackage("..order.api..");

    /** Constructor injection only: immutable, testable, no hidden dependencies. */
    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}
```

Break a rule on purpose (for example, import a controller class in `Order`), run the test, and read the failure message. Then revert.

---

## Step 3: Integration tests with Testcontainers

Add to `services/order-svc/build.gradle.kts`:

```kotlin
testImplementation("org.awaitility:awaitility")          // version managed by the Boot BOM
```

`services/order-svc/src/test/java/com/quickbite/order/TestContainersConfig.java`

```java
package com.quickbite.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres + real Kafka, started once and shared. @ServiceConnection wires the URLs automatically. */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("orders"); }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() { return new KafkaContainer("apache/kafka-native:4.1.0"); }
}
```

`services/order-svc/src/test/java/com/quickbite/order/OrderFlowIT.java`

```java
package com.quickbite.order;

import com.quickbite.order.app.OrderService;
import com.quickbite.order.app.PlaceOrderRequest;
import com.quickbite.order.client.Clients.*;
import com.quickbite.order.domain.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"quickbite.dispatch.interval-ms=3600000"})   // keep the dispatcher quiet
@Import(TestContainersConfig.class)
class OrderFlowIT {
    @MockitoBean CatalogClient catalog;     // other services are mocked at the HTTP-client boundary
    @MockitoBean LocationClient location;

    @Autowired OrderService orders;
    @Autowired OrderRepository repo;
    @Autowired JdbcClient jdbc;
    @Value("${spring.kafka.bootstrap-servers}") String bootstrap;

    private PlaceOrderRequest request() {
        return new PlaceOrderRequest("r1", List.of(new PlaceOrderRequest.Item("i1", 2)), 12.9, 77.6, null); // null = default card
    }

    @Test
    void placingAnOrderPersistsItAndPublishesOrderCreatedViaOutbox() {
        when(catalog.prices(eq("r1"), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", new BigDecimal("150"), true)));

        Order o = orders.place("alice", request(), UUID.randomUUID().toString());

        assertThat(o.getTotalAmount()).isEqualByComparingTo("300");
        // the relay publishes the outbox row to REAL Kafka
        try (var consumer = consumer()) {
            consumer.subscribe(List.of("orders.events"));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var found = new ArrayList<String>();
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.key().equals(o.getId().toString()))
                        found.add(new String(r.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8));
                });
                assertThat(found).contains("order.created");
            });
        }
        Integer pending = jdbc.sql("select count(*) from outbox where published_at is null").query(Integer.class).single();
        assertThat(pending).isZero();
    }

    @Test
    void sameIdempotencyKeyReturnsSameOrder() {
        when(catalog.prices(any(), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", BigDecimal.TEN, true)));
        String key = UUID.randomUUID().toString();
        Order first = orders.place("alice", request(), key);
        Order second = orders.place("alice", request(), key);
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void duplicatePaymentEventIsAppliedOnce() {
        when(catalog.prices(any(), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", BigDecimal.TEN, true)));
        Order o = orders.place("alice", request(), null);
        var evt = new Events.PaymentEvent(o.getId().toString(), "p1", null);

        orders.onPaymentEvent("evt-123", "payment.authorized", evt);
        orders.onPaymentEvent("evt-123", "payment.authorized", evt);    // redelivery: must be a no-op, not an error

        assertThat(repo.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        Integer statusEvents = jdbc.sql("select count(*) from outbox where msg_key = ? and event_type = 'order.status-changed'")
                .param(o.getId().toString()).query(Integer.class).single();
        assertThat(statusEvents).isEqualTo(1);
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }
}
```

### Security integration test (authorization rules through the real filter chain)

`services/order-svc/src/test/java/com/quickbite/order/OrderSecurityIT.java`

```java
package com.quickbite.order;

import com.quickbite.order.client.Clients.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"quickbite.dispatch.interval-ms=3600000"})
@Import(TestContainersConfig.class)
class OrderSecurityIT {
    @MockitoBean CatalogClient catalog;
    @MockitoBean LocationClient location;
    @Autowired WebApplicationContext ctx;
    MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    static final String BODY = """
        {"restaurantId":"r1","items":[{"menuItemId":"i1","quantity":1}],"deliveryLat":12.9,"deliveryLon":77.6}""";

    @Test void noTokenIs401() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test void riderCannotPlaceOrders() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(jwt().jwt(j -> j.subject("bob")).authorities(new SimpleGrantedAuthority("ROLE_rider"))))
           .andExpect(status().isForbidden());
    }

    @Test void invalidPayloadIs400() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"restaurantId\":\"\",\"items\":[]}")
                .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_customer"))))
           .andExpect(status().isBadRequest());
    }

    @Test void adminDebugEndpointNeedsAdmin() throws Exception {
        mvc.perform(post("/api/orders/admin/debug/hang").param("on", "false")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))))
           .andExpect(status().isForbidden());
    }
}
```

Run:

```bash
./gradlew :services:order-svc:test                 # unit + ArchUnit + ITs (Docker must be running)
./gradlew :services:order-svc:test --tests '*IT'   # only integration tests
./gradlew test                                     # everything, all modules
open services/order-svc/build/reports/tests/test/index.html
```

**Exercise:** write the same style of IT for payment-svc. Replay one `order.created` event twice through `PaymentService.onOrderCreated` and assert that exactly one row exists in `payments`.

---

## Step 4: Contract tests for events (lightweight, consumer-driven)

**Problem:** order-svc renames `amount` → `totalAmount`. Its unit tests pass. payment-svc now reads `null` and every payment breaks, but only in production.

**Fix:** a shared JSON **fixture** that is the contract. The producer's test asserts that it *produces* this shape, and the consumer's test asserts that it *can read* it.

`contracts/order.created.v1.json` (repo root)

```json
{
  "orderId": "7322148834123456789",
  "customerId": "5f1c-alice",
  "restaurantId": "r1",
  "amount": 300.00,
  "currency": "INR",
  "deliveryLat": 12.9279,
  "deliveryLon": 77.6271,
  "paymentMethodId": "pm_3f9a0c1d2e4b5a6978c1d2e3"
}
```

Producer side, `order-svc/src/test/.../OrderCreatedContractTest.java`:

```java
@Test
void producerMatchesContract() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    var produced = json.readTree(json.writeValueAsString(new Events.OrderCreated(
            "7322148834123456789", "5f1c-alice", "r1", new BigDecimal("300.00"), "INR", 12.9279, 77.6271,
            "pm_3f9a0c1d2e4b5a6978c1d2e3")));
    var contract = json.readTree(Files.readString(Path.of("../../contracts/order.created.v1.json")));
    assertThat(produced.propertyNames()).containsExactlyInAnyOrderElementsOf(contract.propertyNames()); // same fields
}
```

Consumer side, `payment-svc/src/test/.../OrderCreatedContractTest.java`:

```java
@Test
void consumerCanReadContract() throws Exception {
    var e = JsonMapper.builder().build().readValue(
            Files.readString(Path.of("../../contracts/order.created.v1.json")), Events.OrderCreated.class);
    assertThat(e.amount()).isEqualByComparingTo("300.00");
    assertThat(e.orderId()).isEqualTo("7322148834123456789");
    assertThat(e.paymentMethodId()).startsWith("pm_");   // a token id, never card data
}
```

> In Jackson 3, `JsonNode.fieldNames()` became `propertyNames()`. If your IDE flags it, use the name it suggests.

**Rules for evolving the contract:**

- **Adding** a field is safe (tolerant readers ignore unknown fields).
- **Renaming or removing** a field is a breaking change: publish `order.created.v2` alongside v1 and migrate consumers first.
- At team scale, use **Pact** or **Spring Cloud Contract**, which automate exactly this check with a broker in CI.

---

## Step 5: End-to-end test script (full stack via NGINX)

`scripts/e2e.sh`

```bash
#!/usr/bin/env bash
# Runs against the containerized stack (scripts/up.sh). Exit code 0 = all passed.
set -uo pipefail
API=http://localhost:8000; PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  ✔ $1"; PASS=$((PASS+1)); else echo "  ✘ $1 (expected $3, got $2)"; FAIL=$((FAIL+1)); fi; }
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

ALICE=$(scripts/token.sh alice alice); BOB=$(scripts/token.sh bob bob); ADMIN=$(scripts/token.sh admin admin)
ORDER_BODY='{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}'

echo "== Security"
check "no token -> 401"            "$(code $API/api/orders)" 401
check "tampered token -> 401"      "$(code -H "Authorization: Bearer ${ALICE}x" $API/api/orders)" 401
check "rider cannot order -> 403"  "$(code -X POST -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' -d "$ORDER_BODY" $API/api/orders)" 403
check "internal api hidden"        "$(code -H "Authorization: Bearer $ALICE" "$API/internal/riders/nearest?lat=1&lon=1")" 403
check "public browse -> 200"       "$(code $API/api/restaurants)" 200

echo "== Happy path (rider simulator in background)"
scripts/rider-sim.sh > /tmp/rider.log 2>&1 & SIM=$!
sleep 5
ID=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: e2e-$RANDOM$RANDOM" \
     -H 'Content-Type: application/json' -d "$ORDER_BODY" | jq -r .id)
for i in $(seq 1 60); do S=$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$ID | jq -r .status); [ "$S" = "DELIVERED" ] && break; sleep 2; done
check "order delivered"            "$S" DELIVERED
check "payment captured"           "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$ID | jq -r .status)" CAPTURED
check "bob cannot read alice's payment" "$(code -H "Authorization: Bearer $BOB" $API/api/payments/orders/$ID)" 403
kill $SIM 2>/dev/null

echo "== Compensation"
curl -s -X POST $API/api/payments/admin/psp -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"latencyMs":50,"failureRate":1.0}' >/dev/null
ID2=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d "$ORDER_BODY" | jq -r .id)
sleep 4
check "PSP down -> order cancelled" "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$ID2 | jq -r .status)" CANCELLED
curl -s -X POST $API/api/payments/admin/psp -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"latencyMs":150,"failureRate":0.0}' >/dev/null

echo "== Payments with test cards"
pmid() { curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/methods | jq -r --arg l "$1" '.[] | select(.last4==$l) | .id'; }
order_with() { curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"restaurantId\":\"r1\",\"items\":[{\"menuItemId\":\"r1-i1\",\"quantity\":1}],\"deliveryLat\":12.93,\"deliveryLon\":77.62,\"paymentMethodId\":\"$1\"}" | jq -r .id; }
check "alice has seeded cards"     "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/methods | jq '[.[] | select(.last4=="4242" and .isDefault)] | length')" 1
D=$(order_with "$(pmid 0002)"); F=$(order_with "$(pmid 9995)"); sleep 4
check "card_declined -> cancelled" "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$D | jq -r .status)" CANCELLED
check "decline reason recorded"    "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$F | jq -r .failureReason)" "DECLINED: insufficient_funds"
check "Luhn-invalid card -> 400"   "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"number":"4242424242424241","expMonth":12,"expYear":2030,"cvc":"123"}' $API/api/payments/methods)" 400
ADMIN_PM=$(curl -s -H "Authorization: Bearer $ADMIN" $API/api/payments/methods | jq -r '.[0].id')
X=$(order_with "$ADMIN_PM"); sleep 4
check "other user's card rejected" "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$X | jq -r .failureReason)" NO_PAYMENT_METHOD
# Dump the ENTIRE payments database and search for a full card number: must never appear
check "no PAN anywhere in DB"      "$(docker exec quickbite-postgres-1 pg_dump -U quickbite payments | grep -c 4242424242424242)" 0
check "sold-out item -> 409"       "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i6","quantity":1}],"deliveryLat":12.93,"deliveryLon":77.62}' $API/api/orders)" 409

echo "== Gateway input validation (file 04, step 5)"
check "path traversal -> 400"      "$(code --path-as-is -H "Authorization: Bearer $ALICE" "$API/api/orders/../internal/x")" 400
check "param pollution -> 400"     "$(code "$API/api/restaurants?city=blr&city=mum")" 400
check "unknown field -> 400"       "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1,"price":1}],"deliveryLat":12.9,"deliveryLon":77.6}' $API/api/orders)" 400
check "duplicate keys -> 400"      "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1,"quantity":999}],"deliveryLat":12.9,"deliveryLon":77.6}' $API/api/orders)" 400
check "wrong content type -> 415"  "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: text/plain' -d x $API/api/orders)" 415
check "non-numeric id -> 404"      "$(code -H "Authorization: Bearer $ALICE" $API/api/orders/abc)" 404

echo "== Session revocation"
T=$(scripts/token.sh alice alice)
curl -s -X POST -H "Authorization: Bearer $T" $API/api/auth/logout >/dev/null
check "revoked token -> 401"       "$(code -H "Authorization: Bearer $T" $API/api/orders)" 401

echo; echo "PASSED: $PASS  FAILED: $FAIL"; [ $FAIL -eq 0 ]
```

```bash
chmod +x scripts/e2e.sh && scripts/e2e.sh
```

---

## Step 6: Load testing with k6

### 6.1 Create load users

The gateway rate-limits **per user** (20 req/s). One user can't generate meaningful load, so we create 50 users through the Keycloak Admin API. Each also gets a default test card, so their orders are paid (without a card every order would end `CANCELLED: NO_PAYMENT_METHOD`).

`scripts/create-load-users.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
KC=http://localhost:8180; N=${1:-50}
ADM=$(curl -s -X POST $KC/realms/master/protocol/openid-connect/token \
  -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin | jq -r .access_token)
ROLE=$(curl -s -H "Authorization: Bearer $ADM" $KC/admin/realms/quickbite/roles/customer)
for i in $(seq 1 $N); do
  U="load$i"
  curl -s -o /dev/null -X POST $KC/admin/realms/quickbite/users -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$U\",\"enabled\":true,\"email\":\"$U@quickbite.dev\",\"emailVerified\":true,\"firstName\":\"Load\",\"lastName\":\"$i\",
         \"credentials\":[{\"type\":\"password\",\"value\":\"$U\",\"temporary\":false}]}"
  ID=$(curl -s -H "Authorization: Bearer $ADM" "$KC/admin/realms/quickbite/users?username=$U&exact=true" | jq -r '.[0].id')
  curl -s -o /dev/null -X POST $KC/admin/realms/quickbite/users/$ID/role-mappings/realm -H "Authorization: Bearer $ADM" \
    -H 'Content-Type: application/json' -d "[$ROLE]"
  scripts/add-card.sh "$U" "$U" 4242424242424242 > /dev/null     # default card -> their orders can be paid
done
echo "created $N users (password = username), each with a default Visa 4242"
```

### 6.2 The load script

`scripts/load/browse-and-order.js`

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const API = __ENV.API || 'http://localhost:8000';
const KC = 'http://localhost:8180/realms/quickbite/protocol/openid-connect/token';
const USERS = 50;
const orderLatency = new Trend('order_latency', true);

export const options = {
  scenarios: {
    browse: { executor: 'ramping-arrival-rate', startRate: 20, timeUnit: '1s', preAllocatedVUs: 100,
              stages: [ { target: 200, duration: '1m' }, { target: 200, duration: '1m' }, { target: 0, duration: '20s' } ],
              exec: 'browse' },
    order:  { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '2m', preAllocatedVUs: 50, exec: 'order' },
  },
  thresholds: {                       // <- these ARE your NFRs, enforced
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<150', 'p(99)<300'],
    'order_latency': ['p(95)<500', 'p(99)<1000'],
  },
};

export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const r = http.post(KC, { grant_type: 'password', client_id: 'cli-test', username: `load${i}`, password: `load${i}` });
    tokens.push(r.json('access_token'));
  }
  return { tokens };
}

export function browse() {
  const r = http.get(`${API}/api/restaurants`, { tags: { name: 'list' } });
  check(r, { 'list 200': (x) => x.status === 200 });
  const id = `r${1 + Math.floor(Math.random() * 6)}`;
  check(http.get(`${API}/api/restaurants/${id}`, { tags: { name: 'detail' } }), { 'detail 200': (x) => x.status === 200 });
}

export function order(data) {
  const token = data.tokens[__VU % data.tokens.length];
  const body = JSON.stringify({ restaurantId: 'r2', items: [{ menuItemId: 'r2-i1', quantity: 1 }], deliveryLat: 12.93, deliveryLon: 77.62 });
  const r = http.post(`${API}/api/orders`, body, { headers: {
      'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` },
      tags: { name: 'place_order' } });
  orderLatency.add(r.timings.duration);
  check(r, { 'order 201': (x) => x.status === 201 });
}
```

```bash
chmod +x scripts/create-load-users.sh && scripts/create-load-users.sh 50
k6 run scripts/load/browse-and-order.js
```

**Reading the results:**

- `X-Cache-Status` HITs at NGINX make `browse` extremely fast. Run it again with the edge cache disabled (comment out `proxy_cache edge;`) to see what the CDN layer is worth.
- `order_latency` includes a synchronous catalog call and a DB transaction. Find the dominant cost with the logs (or with tracing in file 12).
- Record the maximum **orders/s per order-svc container** at which p99 stays under target. That is the **measured per-pod capacity** that replaces the estimate in your capacity sheet.

---

## Step 7: Chaos catalogue

Run each while `scripts/rider-sim.sh` and a k6 run are active. Write the observed behaviour next to the expected one.

| # | Fault | Command | Expected (the design promise) |
|---|---|---|---|
| C1 | Payment provider outage | `POST /api/payments/admin/psp {"failureRate":1.0}` | Breaker opens, orders are CANCELLED fast, no thread pile-up |
| C2 | Slow PSP | `{"latencyMs":3000}` | Slow-call threshold opens the breaker; order placement latency unaffected (async) |
| C3 | Kafka down 60 s | `docker stop quickbite-kafka-1` → start | Orders still accepted (201); outbox backlog drains; no lost events |
| C4 | Redis down | `docker stop quickbite-redis-1` | Browse still works (cache fail-open); gateway fails open on session check (by ADR); rate limiting off |
| C5 | location-svc frozen | `docker pause quickbite-location-svc-1` | Dispatcher calls time out → breaker opens → orders wait in PAID; `unpause` → assignments resume |
| C6 | Catalog down | `docker stop quickbite-catalog-svc-1` | Browse served stale from NGINX (`use_stale`); new orders get a fast 503 |
| C7 | Hung loop | `/api/orders/admin/debug/hang?on=true` | Liveness DOWN in ~30 s (a restart in K8s, file 12) |
| C8 | JVM crash | `docker exec quickbite-order-svc-1 sh -c 'kill -9 -1'` | Container restarts; saga resumes; no duplicate charges |
| C9 | Poison message | File 07, step F | Lands in `.dlt`; partition keeps flowing |
| C10 | Keycloak down | `docker stop quickbite-keycloak-1` | **Existing** tokens keep working (JWKS cached, offline validation); new logins fail |

C10 is a good one to reflect on: stateless JWT validation means your identity provider is **not** in the hot path of every request.

---

## Step 8: Security testing

**Scripted negative tests** are already in `e2e.sh`: 401 without a token, 401 with a tampered token, 403 for the wrong role, 403 for another user's object (BOLA), hidden internal APIs, and revoked sessions.

**Add these manually:**

```bash
# Expired token: wait 5+ minutes with a saved token, then call -> 401 (exp enforced)

# Token for the wrong audience: remove the audience mapper from cli-test in the Keycloak admin UI,
# get a new token -> gateway 401 (aud enforced). Restore the mapper afterwards.

# Mass assignment: send a price in the order body -> rejected at the gateway (schema: additionalProperties=false).
# (Behind the gateway, order-svc would ignore it anyway: server-side pricing. Two independent defenses.)
curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $(scripts/token.sh alice alice)" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1,"price":1}],"deliveryLat":12.9,"deliveryLon":77.6}' | jq '{status, errors}'   # 400

# Oversized body -> 413 from NGINX (client_max_body_size 1m)
head -c 2000000 /dev/zero | curl -s -o /dev/null -w "%{http_code}\n" -X POST --data-binary @- localhost:8000/api/orders
```

**OWASP ZAP baseline scan** (passive, safe to run locally):

```bash
docker run --rm --network host -v "$PWD:/zap/wrk" ghcr.io/zaproxy/zaproxy:stable \
  zap-baseline.py -t http://localhost:8000/api/restaurants -r zap-report.html
open zap-report.html
```

Expect warnings about missing security headers (e.g. `X-Content-Type-Options`). Fix them in NGINX with `add_header` and re-scan. That is the loop: scan → fix → re-scan.

**Dependency and image scanning:**

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image quickbite/order-svc:dev
```

---

## Requirement → test traceability

| Requirement (Phase 0) | Proven by |
|---|---|
| Orders go from placed to delivered | `e2e.sh` happy path; mobile Scenario A/B |
| Server-side pricing | `OrderTest.totalIsComputedFromServerPrices`; mass-assignment test |
| No duplicate orders or charges | `sameIdempotencyKeyReturnsSameOrder`, `duplicatePaymentEventIsAppliedOnce`, file 07 E, mobile M5 |
| Events never lost | `placingAnOrder...ViaOutbox`; chaos C3 |
| p99 < 300 ms browse / < 1 s order | k6 thresholds |
| Survives dependency failures | Chaos C1–C10 |
| AuthN/AuthZ (OWASP API1/API2/API5) | `OrderSecurityIT`, e2e security section |
| Logout is real | e2e revocation; mobile M4 |
| Card validation (Luhn, expiry, CVC length) | `CardRulesTest`, `FakePspTest.validationErrors`, e2e "Luhn-invalid" |
| Declines don't trip the breaker; failures do | File 07 step C; `FakePspTest.declinesAreReturnedNotThrown` |
| No card data stored (PCI) | e2e "no PAN anywhere in DB" (greps a full `pg_dump`) |
| Sold-out items can't be ordered | e2e "sold-out item -> 409" |
| Malformed or hostile input rejected at the edge (OWASP API8/API4) | `RequestSanityFilterTest`, `JsonSchemaRegistryTest`, e2e "Gateway input validation" |
| Mass assignment blocked | `JsonSchemaRegistryTest.massAssignmentRejected`; e2e "unknown field -> 400" |
| Hung processes are detected | `LoopWatchdogTest`; chaos C7 |
| Architecture stays clean | `ArchitectureTest` |
| Event compatibility | Contract tests |

## Verify

- `./gradlew test` passes (with Docker running).
- `scripts/e2e.sh` prints `FAILED: 0`.
- k6 thresholds are green, and you have written down your measured per-pod capacity.
- The chaos table is filled in with observed behaviour.
