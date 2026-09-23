package com.quickbite.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.security.Principal;

@Configuration
public class RateLimitConfig {
    /** Logged-in users are limited per user id (sub); anonymous traffic per client IP. */
    @SuppressWarnings("null")
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
            .map(Principal::getName)
            .map(sub -> "user:" + sub)
            .switchIfEmpty(Mono.fromSupplier(() -> {
                // Local setup: NGINX is our only proxy, so trusting X-Forwarded-For is safe.
                // In production, trust it ONLY when the request comes from your known load balancer IPs.
                String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) return "ip:" + xff.split(",")[0].trim();
                var addr = exchange.getRequest().getRemoteAddress();
                return "ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress());
            }));
    }
}
