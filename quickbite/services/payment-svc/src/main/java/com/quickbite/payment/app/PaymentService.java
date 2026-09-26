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