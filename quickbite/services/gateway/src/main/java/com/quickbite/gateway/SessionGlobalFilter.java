package com.quickbite.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs for every routed request, AFTER Spring Security authenticated it:
 *  1. rejects tokens whose session was revoked (logout)
 *  2. records the session in Redis (who is online, from which device)
 *  3. overwrites identity headers so clients can never spoof them
 */
@Component
public class SessionGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(SessionGlobalFilter.class);
    static final String SESSION_ID = "X-Session-Id";
    static final String USER_ID = "X-User-Id";
    static final String CORRELATION_ID = "X-Correlation-Id";

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProps props;

    public SessionGlobalFilter(ReactiveStringRedisTemplate redis, GatewayProps props) {
        this.redis = redis; this.props = props;
    }

    @SuppressWarnings("null")
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // NOTE the shape: we wrap in Optional instead of using switchIfEmpty after flatMap.
        // chain.filter() returns an EMPTY Mono<Void>, which would trigger switchIfEmpty a
        // second time and execute the chain twice: a classic Reactor bug.
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(auth -> auth
                .filter(a -> a instanceof JwtAuthenticationToken)
                .map(a -> authenticated(exchange, chain, ((JwtAuthenticationToken) a).getToken()))
                .orElseGet(() -> anonymous(exchange, chain)));
    }

    private Mono<Void> authenticated(ServerWebExchange exchange, GatewayFilterChain chain, Jwt jwt) {
        String sid = sessionIdOf(jwt);
        if (sid == null) return reject(exchange, "token has no session id");

        return redis.hasKey("revoked:sid:" + sid)
            .onErrorResume(e -> {
                // Decision: fail OPEN (availability) if Redis is down. A security-critical system
                // might fail CLOSED instead. Write this choice down as an ADR.
                log.warn("Session check skipped, Redis unavailable: {}", e.toString());
                return Mono.just(false);
            })
            .flatMap(revoked -> {
                if (revoked) return reject(exchange, "session revoked");
                ServerHttpRequest req = exchange.getRequest().mutate().headers(h -> {
                    h.set(SESSION_ID, sid);
                    h.set(USER_ID, jwt.getSubject());
                    if (h.getFirst(CORRELATION_ID) == null) h.set(CORRELATION_ID, UUID.randomUUID().toString());
                }).build();
                return touchSession(sid, jwt, exchange)
                    .then(chain.filter(exchange.mutate().request(req).build()));
            });
    }

    private Mono<Void> anonymous(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest().mutate().headers(h -> {
            h.remove(SESSION_ID);   // never trust identity headers sent by clients
            h.remove(USER_ID);
            if (h.getFirst(CORRELATION_ID) == null) h.set(CORRELATION_ID, UUID.randomUUID().toString());
        }).build();
        return chain.filter(exchange.mutate().request(req).build());
    }

    private Mono<Void> touchSession(String sid, Jwt jwt, ServerWebExchange exchange) {
        String key = "session:" + sid;
        String agent = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("User-Agent")).orElse("?");
        return redis.opsForHash().putAll(key, Map.of(
                    "userId", jwt.getSubject(),
                    "username", Optional.ofNullable(jwt.getClaimAsString("preferred_username")).orElse("?"),
                    "lastSeen", Instant.now().toString(),
                    "userAgent", agent))
            .then(redis.expire(key, props.session().idleTtl()))
            .onErrorResume(e -> Mono.just(false)) // bookkeeping must never break a request
            .then();
    }

    static String sessionIdOf(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        return sid != null ? sid : jwt.getClaimAsString("session_state"); // older Keycloak versions
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        log.info("401 at gateway: {}", reason);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"" + reason + "\"");
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() { return -1; } // early among gateway filters (security already ran as a WebFilter)
}