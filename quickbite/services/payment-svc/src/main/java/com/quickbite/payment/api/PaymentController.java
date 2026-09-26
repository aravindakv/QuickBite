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