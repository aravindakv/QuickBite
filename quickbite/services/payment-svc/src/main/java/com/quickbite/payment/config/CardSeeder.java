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
