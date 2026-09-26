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