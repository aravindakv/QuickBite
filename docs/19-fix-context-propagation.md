# 19 — Fix: Session-id and Correlation-id Reaching the Outbox

**Symptom.** `order.created` events carry empty `sessionId` / `correlationId` Kafka headers, and the `outbox` row shows both columns NULL, even though the HTTP response echoes `X-Correlation-Id` correctly and the gateway writes `session:<sid>` keys to Redis.

```
 event_type   | session_id | correlation_id | published_at
---------------+------------+----------------+--------------
 order.created |            |                |
```

**What that proves and rules out:**

| Evidence | Conclusion |
|---|---|
| Response header `X-Correlation-Id: probe-456` echoed | `CorrelationFilter` ran and populated the MDC on the request thread |
| `session:<sid>` keys exist in Redis | The gateway's `SessionGlobalFilter` ran and sent `X-Session-Id` |
| Both columns NULL in `outbox` | At the moment of the INSERT, `MDC.get(...)` returned null |
| `[sid= cid=]` on `scheduling-*` / `kafka-*` log lines | Expected: background threads have no request context |

So the ids exist at the edge of the service and are gone deeper in. The MDC is a `ThreadLocal`: any hand-off (a transaction callback executed elsewhere, a virtual-thread carrier switch, a filter clearing it early, or a second copy of the class from a stale jar) silently empties it, and `MDC.get` returns null rather than failing.

---

## The fix: pass the context explicitly, keep the MDC for logging only

The design rule: **logging context may be ambient, business data must not be.** The `sessionId` written into an event is business data (an audit trail), so it travels as a parameter, from the layer where it is definitely known.

```
HTTP request ──► Controller (@RequestHeader)  ──┐
                                                 ├──► RequestContext ──► OrderService ──► OutboxWriter ──► Kafka headers
Kafka record ──► Listener (record headers)   ───┘
```

`CorrelationFilter` keeps feeding `%X{sessionId}` in the log pattern, but it must write the MDC under `Headers.MDC_SESSION` / `Headers.MDC_CORRELATION` (`sessionId` / `correlationId`), **not** the HTTP header names `Headers.SESSION_ID` / `Headers.CORRELATION_ID` (`X-Session-Id` / `X-Correlation-Id`). Using the header names as MDC keys makes every `MDC.get("sessionId")` and the `[sid= cid=]` log pattern come back empty, even though the response header is still echoed.

## Step 1: `RequestContext` in `libs/common`

`libs/common/src/main/java/com/quickbite/common/context/RequestContext.java`

```java
package com.quickbite.common.context;

import org.slf4j.MDC;

/**
 * The ids that belong to one user action. Captured where they are certain (a controller or a Kafka
 * listener) and passed down explicitly, so no layer depends on a ThreadLocal surviving the call chain.
 */
public record RequestContext(String sessionId, String correlationId) {

    public static final RequestContext NONE = new RequestContext(null, null);

    public static RequestContext of(String sessionId, String correlationId) {
        return new RequestContext(blankToNull(sessionId), blankToNull(correlationId));
    }

    /** Best effort, for call sites that still run on the request thread (background jobs get NONE). */
    public static RequestContext fromMdc() {
        return of(MDC.get(Headers.MDC_SESSION), MDC.get(Headers.MDC_CORRELATION));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() || "-".equals(v) ? null : v;
    }
}
```

## Step 2: `OutboxWriter` takes the context as a parameter

`libs/common/src/main/java/com/quickbite/common/outbox/OutboxWriter.java` — replace the `write` method:

```java
    /** Preferred: the caller states the context. */
    public void write(RequestContext ctx, String topic, String key, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must run inside the business transaction");
        }
        jdbc.sql("""
                insert into outbox(id, topic, msg_key, event_type, payload, session_id, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
            .params(UUID.randomUUID(), topic, key, eventType, json.writeValueAsString(payload),
                    ctx.sessionId(), ctx.correlationId())
            .update();
    }

    /** Legacy call sites: falls back to the MDC. Kept so existing code compiles; prefer the overload above. */
    @Deprecated
    public void write(String topic, String key, String eventType, Object payload) {
        write(RequestContext.fromMdc(), topic, key, eventType, payload);
    }
```

Add `import com.quickbite.common.context.RequestContext;` at the top.

## Step 3: order-svc captures the context at its edges

`api/OrderController.java` — read the headers the gateway sets, and hand them to the service:

```java
    @PostMapping
    @PreAuthorize("hasRole('customer')")
    public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
                                               @RequestHeader(value = Headers.SESSION_ID, required = false) String sessionId,
                                               @RequestHeader(value = Headers.CORRELATION_ID, required = false) String correlationId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody PlaceOrderRequest req) {
        Order o = service.place(jwt.getSubject(), req, key, RequestContext.of(sessionId, correlationId));
        return ResponseEntity.created(URI.create("/api/orders/" + o.getId())).body(OrderResponse.from(o));
    }
```

Do the same for `pickup` and `deliver` (they publish `order.status-changed`), passing the context into `service.riderAction(...)`.

`app/OrderService.java` — thread the context through and use the new `write`:

```java
    public Order place(String customerId, PlaceOrderRequest req, String idempotencyKey, RequestContext ctx) {
        // ... unchanged pricing / validation ...
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

    public void onPaymentEvent(String eventId, String eventType, PaymentEvent e, RequestContext ctx) {
        tx.executeWithoutResult(status -> {
            if (!idempotency.firstTime(eventId)) return;
            Order o = repo.findById(Long.parseLong(e.orderId())).orElseThrow();
            switch (eventType) {
                case "payment.authorized" -> o.transitionTo(OrderStatus.PAID);
                case "payment.failed" -> o.transitionTo(OrderStatus.CANCELLED);
                default -> { return; }
            }
            publishStatus(o, ctx);
        });
    }

    private void publishStatus(Order o, RequestContext ctx) {
        outbox.write(ctx, Events.ORDERS_TOPIC, o.getId().toString(), "order.status-changed", Events.statusOf(o));
    }
```

`assignRider` is called by the **dispatcher**, a scheduled job with no user request behind it, so it passes `RequestContext.NONE`. That is correct and worth keeping visible: the chain legitimately ends there.

## Step 4: Kafka listeners continue the chain

The ids arrive as record headers, so listeners rebuild the context instead of relying on the interceptor's MDC.

`messaging/PaymentEventsListener.java` (order-svc):

```java
    @KafkaListener(topics = Events.PAYMENTS_TOPIC)
    public void on(ConsumerRecord<String, String> rec) {
        String eventId = header(rec, Headers.K_EVENT_ID);
        String type = header(rec, Headers.K_EVENT_TYPE);
        RequestContext ctx = RequestContext.of(header(rec, Headers.K_SESSION_ID), header(rec, Headers.K_CORRELATION_ID));
        orders.onPaymentEvent(eventId, type, json.readValue(rec.value(), Events.PaymentEvent.class), ctx);
    }
```

Apply the same three-line change in payment-svc's `OrderEventsListener` (pass the context into `onOrderCreated` / `onOrderStatusChanged`, then into their `outbox.write` calls).

`header(...)` returns `"-"` when absent, which `RequestContext.of` converts to null, so a missing id never becomes the literal string `-` in the database.

## Step 5: Tests that would have caught this

`services/order-svc/src/test/java/com/quickbite/order/OrderContextIT.java` (add to the existing Testcontainers setup from file 11):

```java
    @Test
    void sessionAndCorrelationIdsReachTheOutbox() {
        when(catalog.prices(any(), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", BigDecimal.TEN, true)));

        orders.place("alice", request(), null, RequestContext.of("sid-123", "cid-456"));

        var row = jdbc.sql("select session_id, correlation_id from outbox order by created_at desc limit 1")
                      .query((rs, n) -> rs.getString(1) + "/" + rs.getString(2)).single();
        assertThat(row).isEqualTo("sid-123/cid-456");
    }
```

And an end-to-end assertion for `e2e.sh`:

```bash
CID="probe-$RANDOM"
curl -sS -o /dev/null -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H "X-Correlation-Id: $CID" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.93,"deliveryLon":77.62}'
sleep 1
check "correlation id reaches the outbox" \
  "$(docker exec quickbite-postgres-1 psql -U quickbite -d orders -tAc \
     "select correlation_id from outbox order by created_at desc limit 1")" "$CID"
```

## Step 6: Verify

```bash
./gradlew :libs:common:build :services:order-svc:build :services:payment-svc:build
# restart order-svc and payment-svc

docker stop quickbite-kafka-1          # keep the row unpublished so you can read it
ALICE=$(scripts/token.sh alice alice)
curl -sS -o /dev/null -X POST localhost:8000/api/orders -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: probe-789' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.93,"deliveryLon":77.62}'
docker exec quickbite-postgres-1 psql -U quickbite -d orders -c \
  "select event_type, session_id, correlation_id from outbox;"
docker start quickbite-kafka-1
```

| Check | Pass condition |
|---|---|
| Outbox row | `session_id` = alice's `sid` from the token, `correlation_id` = `probe-789` |
| Kafka headers | `sessionId:<sid>,correlationId:probe-789` in the console consumer |
| payment-svc log | Its `Order … -> payment.authorized` line shows the same `[sid=…]` |
| Dispatcher log | Still `[sid= cid=]`: correct, no user request behind it |

Then follow one journey across services:

```bash
docker compose -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.apps.yml \
  logs --no-log-prefix order-svc payment-svc | grep "probe-789"
```

---

## Optional: find out why the MDC emptied

The explicit fix makes the answer unnecessary, but it is worth knowing. Add one line at the top of `OutboxWriter.write` and place an order:

```java
LoggerFactory.getLogger(OutboxWriter.class).info("outbox write thread={} mdcSid={} mdcCid={}",
        Thread.currentThread(), MDC.get(Headers.MDC_SESSION), MDC.get(Headers.MDC_CORRELATION));
```

| Observation | Cause |
|---|---|
| Thread name differs from the controller's (`omcat-handler-*`) | The work moved to another thread; the MDC did not follow |
| Same thread, but `mdcSid=null` | Something cleared the MDC earlier (a second filter, or `CorrelationFilter` registered twice with the inner one clearing in `finally`) |
| `mdcSid` populated here, yet NULL in the row | A stale `libs/common` jar: the running code isn't the code you edited (`./gradlew :libs:common:build` then restart) |

## Why this is the better design anyway

1. **Explicit beats ambient for anything persisted.** An event's `sessionId` is part of the audit record; a silent null is a data-quality bug that no test catches unless the value is a parameter.
2. **It works off the request thread.** Kafka listeners, scheduled jobs and `@Async` code all have to state their context, and `RequestContext.NONE` documents where a chain genuinely ends.
3. **The MDC keeps its proper job.** Ambient logging context, where a missing value costs a grep, not a corrupted record.

The same rule applies to the tracing baggage approach mentioned in file 02: it is fine for *observability*, but a value you store in a database should be passed, not inherited.
