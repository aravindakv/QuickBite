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