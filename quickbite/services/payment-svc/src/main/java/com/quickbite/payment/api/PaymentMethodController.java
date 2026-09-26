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