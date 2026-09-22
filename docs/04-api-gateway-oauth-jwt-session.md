# 04 — API Gateway: OAuth2, JWT, Session-id, Rate Limiting, Input Validation

**Goal:** a Spring Cloud Gateway that does the following:

- Validates every JWT (signature, `iss`, `aud`, `exp`).
- Converts Keycloak's `sid` into an `X-Session-Id` header.
- Supports **real logout** for stateless tokens via a Redis session denylist.
- Rate-limits per user with a Redis token bucket.
- Routes HTTP and WebSocket traffic to the services.
- **Validates all input at the edge** (Step 5): path, query, headers, content type, and JSON bodies against JSON Schemas.

---

## Concepts first

### Why a gateway at all?

The gateway is the **single entry point**. Clients know one host, and cross-cutting concerns are implemented once instead of six times:

- authentication
- rate limiting
- CORS
- request-size limits
- routing
- correlation ids

The services behind it stay focused on business logic.

### The logout problem with JWTs

A JWT is **self-contained**: any service can verify it offline, which is why it scales. The flip side is that you can't "delete" a JWT. It stays valid until `exp`.

**Our fix, a session denylist:**

1. Keycloak stamps each token with `sid`, the login session. Every token from one login shares it, including tokens minted by refreshes.
2. On logout, the gateway writes `revoked:sid:<sid>` to Redis, with a TTL equal to the maximum session lifetime (10 h).
3. On every request, the gateway checks that key. This is one Redis `EXISTS` call, ~0.2 ms.

Result: logout kills **all** tokens of that session immediately, even if the app keeps refreshing. We still keep stateless JWTs everywhere else. This is the standard trade-off: a tiny, fast, shared lookup buys you revocation.

### Token bucket rate limiting

Each user has a bucket of `burstCapacity` tokens that refills at `replenishRate` tokens per second, and each request takes one. The effect:

- Bursts up to 40 requests are allowed.
- The sustained rate is capped at 20 requests per second.

Spring Cloud Gateway runs the bucket logic as an **atomic Lua script inside Redis**. It is therefore correct across many gateway replicas with no race conditions.

---

## Step 1: Build file

`services/gateway/build.gradle.kts`

```kotlin
plugins { id("quickbite.spring-service") }

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
```

> Since Spring Cloud 2025.0, the gateway starter is `spring-cloud-starter-gateway-server-webflux` (reactive) or `...-server-webmvc` (servlet), and its properties live under `spring.cloud.gateway.server.webflux.*`. Older tutorials use `spring.cloud.gateway.routes`, which no longer applies.

---

## Step 2: Configuration

`services/gateway/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  application:
    name: gateway
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
  cloud:
    gateway:
      server:
        webflux:
          httpclient:
            connect-timeout: 2000     # ms: fail fast if a service is down
            response-timeout: 10s     # default; tightened per route below
          default-filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 20
                redis-rate-limiter.burstCapacity: 40
                redis-rate-limiter.requestedTokens: 1
                key-resolver: "#{@userKeyResolver}"
          routes:
            - id: catalog
              uri: ${CATALOG_URL:http://localhost:8081}
              predicates: ["Path=/api/restaurants,/api/restaurants/**"]
            - id: orders
              uri: ${ORDER_URL:http://localhost:8082}
              predicates: ["Path=/api/orders,/api/orders/**"]
              metadata:
                response-timeout: 3000
            - id: payments
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: ["Path=/api/payments/**"]
            - id: riders
              uri: ${LOCATION_URL:http://localhost:8084}
              predicates: ["Path=/api/riders/**"]
            - id: rider-ws
              uri: ${LOCATION_WS_URL:ws://localhost:8084}
              predicates: ["Path=/ws/rider"]
            - id: updates-ws
              uri: ${REALTIME_WS_URL:ws://localhost:8085}
              predicates: ["Path=/ws/updates"]

management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway
  endpoint:
    health:
      probes:
        enabled: true

quickbite:
  security:
    issuer: ${KC_ISSUER:http://localhost:8180/realms/quickbite}
    jwk-set-uri: ${KC_JWKS:http://localhost:8180/realms/quickbite/protocol/openid-connect/certs}
    audience: quickbite-api
  session:
    revoke-ttl: 10h             # = Keycloak ssoSessionMaxLifespan
    idle-ttl: 30m

logging:
  level:
    org.springframework.cloud.gateway: INFO
```

**Notice:**

- `/internal/**` paths have **no route**, so they cannot be reached from outside. Internal APIs exist only on the service network.
- WebSocket routes use the `ws://` scheme; the gateway proxies the upgrade handshake and then pipes frames.
- These catch-all routes (`/api/orders/**`) are deliberately simple to start with. **Step 5 replaces them** with an allow-list of exact paths, typed ids and methods, each with its own input validation.

---

## Step 3: Code

All files live under `services/gateway/src/main/java/com/quickbite/gateway/`.

`GatewayApplication.java`

```java
package com.quickbite.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}
```

`GatewayProps.java`

```java
package com.quickbite.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("quickbite")
public record GatewayProps(Security security, Session session) {
    public record Security(String issuer, String jwkSetUri, String audience) {}
    public record Session(Duration revokeTtl, Duration idleTtl) {}
}
```

`SecurityConfig.java`

```java
package com.quickbite.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.Collection;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers("/actuator/health/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/restaurants", "/api/restaurants/**").permitAll() // browse before login
                .pathMatchers("/internal/**", "/actuator/**").denyAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .build();
    }

    /**
     * Keys: fetched from the INTERNAL url (keycloak:8180 in Docker) and cached; refetched on unknown "kid" (key rotation).
     * Issuer: must equal the PUBLIC url that Keycloak writes into tokens.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProps props) {
        var s = props.security();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(s.jwkSetUri()).build();
        var audience = new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(s.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(s.issuer()), audience));
        return decoder;
    }
}
```

`RateLimitConfig.java`

```java
package com.quickbite.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.security.Principal;

@Configuration
public class RateLimitConfig {
    /** Logged-in users are limited per user id (sub); anonymous traffic per client IP. */
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
```

`SessionGlobalFilter.java`: the heart of this file.

```java
package com.quickbite.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
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
```

`AuthController.java`: logout and session info live *in* the gateway, because the gateway owns sessions.

```java
package com.quickbite.gateway;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final ReactiveStringRedisTemplate redis;
    private final GatewayProps props;

    public AuthController(ReactiveStringRedisTemplate redis, GatewayProps props) { this.redis = redis; this.props = props; }

    /** Revokes EVERY token of this login session, immediately, across all services. */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@AuthenticationPrincipal Jwt jwt) {
        String sid = SessionGlobalFilter.sessionIdOf(jwt);
        return redis.opsForValue().set("revoked:sid:" + sid, jwt.getSubject(), props.session().revokeTtl())
            .then(redis.delete("session:" + sid))
            .thenReturn(ResponseEntity.noContent().build());
    }

    /** Debug view of your own session as the gateway sees it. */
    @GetMapping("/session")
    public Mono<Map<Object, Object>> session(@AuthenticationPrincipal Jwt jwt) {
        String sid = SessionGlobalFilter.sessionIdOf(jwt);
        return redis.opsForHash().entries("session:" + sid).collectMap(Map.Entry::getKey, Map.Entry::getValue)
            .map(m -> { m.put("sid", sid); return m; });
    }
}
```

> **Why doesn't `/api/auth/logout` collide with routing?** Spring's `@RequestMapping` handler mapping has a higher priority than the gateway's route mapping, and no route matches `/api/auth` anyway. Global filters apply only to *routed* requests, which is why the controller checks the session itself (by reading `sid` directly).

---

## Step 4: Run and test

```bash
./gradlew :services:gateway:bootRun
```

In another terminal:

```bash
TOKEN=$(scripts/token.sh alice alice)

# 1) No token on a protected route -> 401 from the gateway
curl -i localhost:8080/api/orders | head -1

# 2) Valid token -> passes auth; the service isn't running yet, so expect a 5xx
#    ("Connection refused") instead of 401. That proves authentication succeeded.
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders | head -1

# 3) Your session as the gateway sees it
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/auth/session | jq

# 4) Tampered token -> 401 (signature check)
curl -i -H "Authorization: Bearer ${TOKEN}x" localhost:8080/api/orders | head -1

# 5) Rate limit: 60 fast requests; some will return 429 once the bucket (40) empties
for i in $(seq 1 60); do curl -s -o /dev/null -w "%{http_code} " -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders; done; echo
curl -si -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders | grep -i x-ratelimit

# 6) Logout, then reuse the SAME (still unexpired) token -> 401 "session revoked"
curl -i -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/auth/logout | head -1
curl -si -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders | grep -i www-authenticate

# 7) Same checks through NGINX (the path the phone will use)
TOKEN=$(scripts/token.sh alice alice)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8000/api/auth/session | jq .username

# Look inside Redis
docker exec -it quickbite-redis-1 redis-cli --scan --pattern 'session:*'
docker exec -it quickbite-redis-1 redis-cli --scan --pattern 'revoked:*'
docker exec -it quickbite-redis-1 redis-cli --scan --pattern 'request_rate_limiter*'
```

---

## Verify

| # | Expected |
|---|---|
| 1 | `401` |
| 2 | `500`/`503` (not 401) |
| 3 | JSON with `userId`, `username: alice`, `sid` |
| 4 | `401` |
| 5 | A mix of `5xx` and `429`; headers `X-RateLimit-Remaining`, `X-RateLimit-Burst-Capacity` |
| 6 | `204`, then `WWW-Authenticate: ... session revoked` |
| 7 | `"alice"` |

**Checkpoint:**

1. The gateway validates JWTs and the services will too. What attack does the second check stop?
2. Logout happens at the gateway, but Keycloak still considers the session alive until it expires. Why is that acceptable here? When would you *also* call Keycloak's logout endpoint? (File 10 does both.)
3. The rate-limit key is the user id, not the IP. Why is per-IP limiting unfair for mobile users behind carrier NAT?

---

## Step 5: Input validation at the edge

**Goal:** the gateway rejects malformed or hostile requests **before** they cost you JWT parsing, a Redis round-trip or a service thread. Only well-formed, allow-listed requests reach the services.

### Concepts first

**Defense in depth: each layer validates what it can see cheaply.**

| Layer | Validates | Typical rejection |
|---|---|---|
| NGINX (file 03) | Body ≤ 1 MB, per-IP rate | `413`, `503` |
| Netty (gateway server) | Header block size, request-line length, illegal header characters | `431`, `400` |
| `RequestSanityFilter` (runs **before** Spring Security) | Methods, URI length, path traversal and encoded tricks, query parameter shape and count, header count and size, `Content-Type`, request smuggling, `Authorization` shape, `Idempotency-Key` / `X-Correlation-Id` format, WebSocket path and Origin | `400 405 413 414 415 431 401 403` |
| Route predicates | **Allow-listed** paths with typed ids (`/api/orders/{id:[0-9]{1,20}}`) and methods | `404` |
| `ValidateQuery` / `ValidateJson` (per route, after auth) | **JSON Schema**: types, ranges, patterns, required fields, *no unknown fields*, parser limits, duplicate keys | `400`, `413` |
| Service (Bean Validation + domain) | Business rules: sold out, Luhn check, ownership, state machine | `400 403 409` |

The gateway checks **shape**, and the service checks **meaning**. The gateway never duplicates business rules (it can't know whether an item is sold out), and services keep their own validation because the gateway isn't the only way in (zero trust, file 02).

**Rules this implementation follows:**

- **Allow-list, not deny-list.** Only known paths, methods, parameters and fields pass. "Block `<script>`" style deny-lists always miss something.
- **Validate before authenticating.** Garbage is rejected at about 1 µs, before RSA signature checks (the JWT) and Redis calls (session, rate limit). This limits the CPU damage of junk floods.
- **Schemas live in JSON files** (`validation/schemas/*.schema.json`, JSON Schema 2020-12). They're language-neutral, reviewable in pull requests, and reusable by tests or clients.
- **Never echo raw input back.** Validator messages are **sanitized** (safe characters only, length-capped), because even a rejected property *name* is attacker-controlled. That prevents log injection, reflected XSS in error pages and card numbers in logs. The card endpoint goes further and **redacts** everything except the field path.
- **Parse strictly.** JSON-level attacks are closed by these limits:
  - nesting ≤ 16
  - strings ≤ 10,000 characters
  - numbers ≤ 32 digits
  - **duplicate keys rejected**
  - no trailing garbage

> **Why duplicate keys matter:** `{"quantity": 1, "quantity": 999}`. Some parsers keep the first value and others keep the last. If the gateway validates `1` but the service reads `999`, validation is bypassed. Rejecting duplicates outright removes the ambiguity.

### 5.1 Server limits

Add to `services/gateway/src/main/resources/application.yml`:

```yaml
server:
  max-http-request-header-size: 16KB     # whole header block (Netty)
  netty:
    max-initial-line-length: 4KB         # "GET /very/long/uri HTTP/1.1"
    validate-headers: true               # reject CR/LF and other illegal characters in headers (header injection)

spring:
  codec:
    max-in-memory-size: 64KB             # hard cap for anything the gateway buffers in memory

quickbite:
  validation:
    max-uri-length: 2048
    max-query-params: 20
    max-param-value-length: 256
    max-headers: 60
    max-header-value-length: 8192
    max-authorization-length: 8192
    max-body-bytes: 65536
    ws-paths: [/ws/rider, /ws/updates]
    ws-allowed-origins: []               # native apps send no Origin header; list your web dashboard origin here
```

### 5.2 Dependency

`services/gateway/build.gradle.kts`: add

```kotlin
// JSON Schema validator (uses Jackson 2 JsonNode internally; it coexists with Boot 4's Jackson 3)
implementation("com.networknt:json-schema-validator:1.5.6")    // or the latest 1.5.x
```

### 5.3 Problem responses

All code in this step lives under `services/gateway/src/main/java/com/quickbite/gateway/validation/`.

`Problems.java`: every rejection uses the same RFC 9457 `application/problem+json` shape as the services.

```java
package com.quickbite.gateway.validation;

import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Problems {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private Problems() {}

    /** detail and errors must never contain raw client input. */
    public static Mono<Void> write(ServerWebExchange exchange, int status, String detail, List<String> errors) {
        ServerHttpResponse res = exchange.getResponse();
        if (res.isCommitted()) return Mono.empty();
        res.setStatusCode(HttpStatusCode.valueOf(status));
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == 401) res.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_request\"");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://quickbite.dev/problems/invalid-request");
        HttpStatus known = HttpStatus.resolve(status);
        body.put("title", known != null ? known.getReasonPhrase() : "Invalid request");
        body.put("status", status);
        body.put("detail", detail);
        if (!errors.isEmpty()) body.put("errors", errors);
        return res.writeWith(Mono.just(res.bufferFactory().wrap(JSON.writeValueAsBytes(body))));
    }
}
```

### 5.4 Request sanity filter (every request, before authentication)

`ValidationProps.java`

```java
package com.quickbite.gateway.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties("quickbite.validation")
public record ValidationProps(int maxUriLength, int maxQueryParams, int maxParamValueLength, int maxHeaders,
                              int maxHeaderValueLength, int maxAuthorizationLength, long maxBodyBytes,
                              List<String> wsPaths, List<String> wsAllowedOrigins) {
    public ValidationProps {                                     // safe defaults if a property is missing
        if (maxUriLength <= 0) maxUriLength = 2048;
        if (maxQueryParams <= 0) maxQueryParams = 20;
        if (maxParamValueLength <= 0) maxParamValueLength = 256;
        if (maxHeaders <= 0) maxHeaders = 60;
        if (maxHeaderValueLength <= 0) maxHeaderValueLength = 8192;
        if (maxAuthorizationLength <= 0) maxAuthorizationLength = 8192;
        if (maxBodyBytes <= 0) maxBodyBytes = 65536;
        wsPaths = wsPaths == null ? List.of("/ws/rider", "/ws/updates") : List.copyOf(wsPaths);
        wsAllowedOrigins = wsAllowedOrigins == null ? List.of() : List.copyOf(wsAllowedOrigins);
    }
}
```

`RequestSanityFilter.java`

```java
package com.quickbite.gateway.validation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cheap, uniform checks on EVERY request (routed or not), ordered before Spring Security (-100),
 * so junk never reaches JWT parsing, Redis or a service.
 */
@Component
public class RequestSanityFilter implements WebFilter, Ordered {
    record Rejection(int status, String reason, String detail) {}

    private static final Set<HttpMethod> ALLOWED_METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS);
    private static final Set<HttpMethod> BODY_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);
    // Traversal and encoding tricks: "..", "//", backslash, encoded ".", "/", "\", NUL, and ";" matrix params
    private static final Pattern BAD_PATH = Pattern.compile("(\\.\\.|//|\\\\|%2[eEfF]|%5[cC]|%00|;)");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,40}$");
    private static final Pattern TOKEN_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");            // idempotency / correlation ids
    // Three base64url segments, signature NON-empty: unsigned ("alg":"none") tokens die here
    private static final Pattern BEARER = Pattern.compile("^Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private final ValidationProps props;
    private final MeterRegistry meters;

    public RequestSanityFilter(ValidationProps props, MeterRegistry meters) { this.props = props; this.meters = meters; }

    @Override
    public int getOrder() { return -200; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        Rejection r = check(req);
        if (r != null) {
            meters.counter("gateway.validation.rejected", "reason", r.reason()).increment();
            return Problems.write(exchange, r.status(), r.detail(), List.of());
        }
        // An invalid correlation id is DROPPED, not rejected: it's ours to generate, and dropping it
        // prevents log forging ("abc\n2026-... INFO fake line"). SessionGlobalFilter creates a fresh one.
        String cid = req.getHeaders().getFirst("X-Correlation-Id");
        if (cid != null && !TOKEN_ID.matcher(cid).matches()) {
            return chain.filter(exchange.mutate().request(req.mutate().headers(h -> h.remove("X-Correlation-Id")).build()).build());
        }
        return chain.filter(exchange);
    }

    Rejection check(ServerHttpRequest req) {
        HttpMethod method = req.getMethod();
        if (!ALLOWED_METHODS.contains(method)) return new Rejection(405, "method", "Method not allowed");

        // ---- URI ----
        String rawPath = req.getURI().getRawPath();
        String rawQuery = req.getURI().getRawQuery();
        int uriLength = rawPath.length() + (rawQuery == null ? 0 : rawQuery.length() + 1);
        if (uriLength > props.maxUriLength()) return new Rejection(414, "uri_length", "URI too long");
        if (BAD_PATH.matcher(rawPath).find() || CONTROL.matcher(rawPath).find())
            return new Rejection(400, "path", "Invalid characters in path");

        var query = req.getQueryParams();
        if (query.size() > props.maxQueryParams()) return new Rejection(400, "query_count", "Too many query parameters");
        for (var e : query.entrySet()) {
            if (!PARAM_NAME.matcher(e.getKey()).matches()) return new Rejection(400, "query_name", "Invalid query parameter name");
            // HTTP parameter pollution: ?city=blr&city=mum -> which one wins differs between frameworks
            if (e.getValue().size() > 1) return new Rejection(400, "query_repeated", "Repeated query parameter '" + e.getKey() + "'");
            for (String v : e.getValue()) {
                if (v != null && (v.length() > props.maxParamValueLength() || CONTROL.matcher(v).find()))
                    return new Rejection(400, "query_value", "Invalid value for query parameter '" + e.getKey() + "'");
            }
        }

        // ---- headers ----
        HttpHeaders h = req.getHeaders();
        int[] names = {0};
        boolean[] oversized = {false};
        h.forEach((name, values) -> {
            names[0]++;
            for (String v : values) if (v != null && v.length() > props.maxHeaderValueLength()) oversized[0] = true;
        });
        if (names[0] > props.maxHeaders() || oversized[0]) return new Rejection(431, "headers", "Request headers too large");

        // Request smuggling: never accept both framing mechanisms
        if (h.getFirst(HttpHeaders.TRANSFER_ENCODING) != null && h.getFirst(HttpHeaders.CONTENT_LENGTH) != null)
            return new Rejection(400, "smuggling", "Conflicting Content-Length and Transfer-Encoding");

        // ---- body ----
        long length = h.getContentLength();
        boolean hasBody = length > 0 || h.getFirst(HttpHeaders.TRANSFER_ENCODING) != null;
        if (hasBody && !BODY_METHODS.contains(method)) return new Rejection(400, "body_not_allowed", "Request body not allowed for " + method.name());
        if (length > props.maxBodyBytes()) return new Rejection(413, "body_size", "Request body too large");
        if (hasBody) {
            MediaType ct;
            try { ct = h.getContentType(); } catch (InvalidMediaTypeException e) { ct = null; }
            if (ct == null || !MediaType.APPLICATION_JSON.isCompatibleWith(ct))
                return new Rejection(415, "content_type", "Content-Type must be application/json");
        }

        // ---- identity and idempotency headers ----
        String auth = h.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && (auth.length() > props.maxAuthorizationLength() || !BEARER.matcher(auth).matches()))
            return new Rejection(401, "authorization", "Malformed Authorization header");
        String idem = h.getFirst("Idempotency-Key");
        if (idem != null && !TOKEN_ID.matcher(idem).matches())
            return new Rejection(400, "idempotency_key", "Idempotency-Key must be 1-64 characters [A-Za-z0-9_-]");

        // ---- WebSockets ----
        if ("websocket".equalsIgnoreCase(h.getFirst(HttpHeaders.UPGRADE))) {
            if (method != HttpMethod.GET || !props.wsPaths().contains(req.getPath().value()))
                return new Rejection(400, "ws_path", "WebSocket not allowed on this path");
            // Cross-Site WebSocket Hijacking: browsers attach cookies AND an Origin. Native apps send no Origin.
            String origin = h.getFirst(HttpHeaders.ORIGIN);
            if (origin != null && !props.wsAllowedOrigins().contains(origin))
                return new Rejection(403, "ws_origin", "WebSocket origin not allowed");
        }
        return null;
    }
}
```

### 5.5 JSON Schemas and the registry

`JsonSchemaRegistry.java`

```java
package com.quickbite.gateway.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Loads validation/schemas/*.schema.json once; the file name (minus .schema.json) is the schema name. */
@Component
public class JsonSchemaRegistry {
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    /** Strict parser: bounded depth and sizes, duplicate keys rejected, nothing after the root value. */
    private final ObjectMapper strict = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(10_000).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonSchemaRegistry() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:validation/schemas/*.schema.json");
        for (Resource f : files) {
            try (InputStream in = f.getInputStream()) {
                schemas.put(Objects.requireNonNull(f.getFilename()).replace(".schema.json", ""), factory.getSchema(in));
            }
        }
        if (schemas.isEmpty()) throw new IllegalStateException("No JSON schemas found under validation/schemas/");
    }

    public void requireSchema(String name) {
        if (!schemas.containsKey(name))
            throw new IllegalArgumentException("Unknown validation schema '" + name + "'. Known: " + new TreeSet<>(schemas.keySet()));
    }

    public Set<String> names() { return Collections.unmodifiableSet(schemas.keySet()); }
    public ObjectMapper mapper() { return strict; }

    /** Returns problems; an empty list means valid. */
    public List<String> validateJson(String schema, byte[] body, boolean redact) {
        JsonNode node;
        try {
            node = strict.readTree(body);
        } catch (StreamConstraintsException e) {
            return List.of("JSON exceeds nesting or size limits");
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            return List.of(m.contains("Duplicate field") ? "Duplicate JSON property" : "Malformed JSON"); // never echo the parser excerpt
        }
        if (node == null || node.isMissingNode()) return List.of("Request body is required");
        return validate(schema, node, redact);
    }

    public List<String> validate(String schema, JsonNode node, boolean redact) {
        Set<ValidationMessage> messages = schemas.get(schema).validate(node);
        return messages.stream()
                .map(m -> redact ? m.getInstanceLocation() + ": invalid value" : sanitize(m.getMessage()))
                .sorted().distinct().limit(20).toList();
    }

    /**
     * Validator messages can quote client-controlled text (e.g. an unknown property NAME like "<script>").
     * Keep only safe characters and cap the length: no HTML, no CR/LF (log injection), no huge strings.
     */
    static String sanitize(String message) {
        String safe = String.valueOf(message).replaceAll("[^A-Za-z0-9 _.,:;'$\\[\\]{}()^*+?|/=#-]", "?");
        return safe.length() > 160 ? safe.substring(0, 160) + "…" : safe;
    }
}
```

**Schema files** live in `services/gateway/src/main/resources/validation/schemas/`.

`place-order.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Place order",
  "type": "object",
  "additionalProperties": false,
  "required": ["restaurantId", "items", "deliveryLat", "deliveryLon"],
  "properties": {
    "restaurantId": { "type": "string", "pattern": "^[a-z0-9-]{1,32}$" },
    "items": {
      "type": "array", "minItems": 1, "maxItems": 50,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["menuItemId", "quantity"],
        "properties": {
          "menuItemId": { "type": "string", "pattern": "^[a-z0-9-]{1,40}$" },
          "quantity": { "type": "integer", "minimum": 1, "maximum": 20 }
        }
      }
    },
    "deliveryLat": { "type": "number", "minimum": -90, "maximum": 90 },
    "deliveryLon": { "type": "number", "minimum": -180, "maximum": 180 },
    "paymentMethodId": { "type": ["string", "null"], "pattern": "^pm_[A-Za-z0-9_]{1,40}$" }
  }
}
```

`add-card.schema.json`: used with `redact: true`.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Add card",
  "type": "object",
  "additionalProperties": false,
  "required": ["number", "expMonth", "expYear", "cvc"],
  "properties": {
    "number": { "type": "string", "pattern": "^[0-9][0-9 -]{11,22}$" },
    "expMonth": { "type": "integer", "minimum": 1, "maximum": 12 },
    "expYear": { "type": "integer", "minimum": 2000, "maximum": 2100 },
    "cvc": { "type": "string", "pattern": "^[0-9]{3,4}$" },
    "holderName": { "type": ["string", "null"], "maxLength": 100, "pattern": "^[^\\u0000-\\u001F<>{}]*$" }
  }
}
```

`price-update.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["price"],
  "properties": { "price": { "type": "number", "exclusiveMinimum": 0, "maximum": 100000 } }
}
```

`rider-location.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["lat", "lon"],
  "properties": {
    "lat": { "type": "number", "minimum": -90, "maximum": 90 },
    "lon": { "type": "number", "minimum": -180, "maximum": 180 }
  }
}
```

`psp-config.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["latencyMs", "failureRate"],
  "properties": {
    "latencyMs": { "type": "integer", "minimum": 0, "maximum": 30000 },
    "failureRate": { "type": "number", "minimum": 0, "maximum": 1 }
  }
}
```

**Query-string schemas.** Query parameters are validated as a JSON object of strings.

`no-query.schema.json`

```json
{ "$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "maxProperties": 0 }
```

`restaurants-query.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "properties": { "city": { "type": "string", "pattern": "^[a-z]{2,10}$" } }
}
```

`menu-items-query.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["ids"],
  "properties": { "ids": { "type": "string", "pattern": "^[a-z0-9-]{1,40}(,[a-z0-9-]{1,40}){0,49}$" } }
}
```

`hang-query.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["on"],
  "properties": { "on": { "enum": ["true", "false"] } }
}
```

`demo-speed-query.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["factor"],
  "properties": { "factor": { "type": "string", "pattern": "^[0-9]{1,2}(\\.[0-9]{1,2})?$" } }
}
```

> **`additionalProperties: false` is mass-assignment protection at the edge.** A client that sends `"price": 1` for an item, or `"status": "DELIVERED"` on an order, gets a `400` instead of relying on every service to ignore unknown fields. The cost: when a service gains a new request field, **its schema must be updated in the same change**. That is exactly the kind of contract you want reviewed.

### 5.6 Route filters: `ValidateJson`, `ValidateQuery`, `NoBody`

Spring Cloud Gateway derives the filter name from the class name minus `GatewayFilterFactory`.

`ValidateJsonGatewayFilterFactory.java`

```java
package com.quickbite.gateway.validation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/** Buffers the body (bounded), validates it against a named schema, then replays the same bytes downstream. */
@Component
public class ValidateJsonGatewayFilterFactory extends AbstractGatewayFilterFactory<ValidateJsonGatewayFilterFactory.Config> {
    public static class Config {
        private String schema;
        private boolean redact = false;          // true for sensitive payloads (cards): report field paths only
        private int maxBytes = 16 * 1024;
        public String getSchema() { return schema; }  public void setSchema(String s) { schema = s; }
        public boolean isRedact() { return redact; }  public void setRedact(boolean r) { redact = r; }
        public int getMaxBytes() { return maxBytes; } public void setMaxBytes(int m) { maxBytes = m; }
    }

    private static final Set<HttpMethod> BODY_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);
    private final JsonSchemaRegistry schemas;
    private final MeterRegistry meters;

    public ValidateJsonGatewayFilterFactory(JsonSchemaRegistry schemas, MeterRegistry meters) {
        super(Config.class);
        this.schemas = schemas; this.meters = meters;
    }

    @Override public List<String> shortcutFieldOrder() { return List.of("schema"); }   // ValidateJson=place-order

    @Override
    public GatewayFilter apply(Config config) {
        schemas.requireSchema(config.getSchema());          // typo in application.yml -> fails when routes are built
        return (exchange, chain) -> {
            ServerHttpRequest req = exchange.getRequest();
            if (!BODY_METHODS.contains(req.getMethod())) return chain.filter(exchange);   // e.g. GET on a GET+POST route

            return DataBufferUtils.join(req.getBody(), config.getMaxBytes())
                .map(buf -> { byte[] b = new byte[buf.readableByteCount()]; buf.read(b); DataBufferUtils.release(buf); return b; })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    List<String> errors = schemas.validateJson(config.getSchema(), bytes, config.isRedact());
                    if (!errors.isEmpty()) {
                        meters.counter("gateway.validation.rejected", "reason", "schema:" + config.getSchema()).increment();
                        return Problems.write(exchange, 400, "Request validation failed", errors);
                    }
                    ServerHttpRequest replay = new ServerHttpRequestDecorator(req) {
                        @Override public Flux<DataBuffer> getBody() {
                            return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                        }
                    };
                    return chain.filter(exchange.mutate().request(replay).build());
                })
                .onErrorResume(DataBufferLimitException.class,
                        e -> Problems.write(exchange, 413, "Request body too large (max " + config.getMaxBytes() + " bytes)", List.of()));
        };
    }
}
```

`ValidateQueryGatewayFilterFactory.java`

```java
package com.quickbite.gateway.validation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Validates the query string as a JSON object of strings: ?city=blr -> {"city":"blr"} */
@Component
public class ValidateQueryGatewayFilterFactory extends AbstractGatewayFilterFactory<ValidateQueryGatewayFilterFactory.Config> {
    public static class Config {
        private String schema;
        public String getSchema() { return schema; } public void setSchema(String s) { schema = s; }
    }

    private final JsonSchemaRegistry schemas;

    public ValidateQueryGatewayFilterFactory(JsonSchemaRegistry schemas) { super(Config.class); this.schemas = schemas; }

    @Override public List<String> shortcutFieldOrder() { return List.of("schema"); }

    @Override
    public GatewayFilter apply(Config config) {
        schemas.requireSchema(config.getSchema());
        return (exchange, chain) -> {
            ObjectNode params = schemas.mapper().createObjectNode();
            // (RequestSanityFilter already rejected repeated names, so the first value is the only value)
            exchange.getRequest().getQueryParams().forEach((k, v) -> params.put(k, v.isEmpty() ? "" : v.getFirst()));
            List<String> errors = schemas.validate(config.getSchema(), params, false);
            return errors.isEmpty() ? chain.filter(exchange)
                                    : Problems.write(exchange, 400, "Invalid query parameters", errors);
        };
    }
}
```

`NoBodyGatewayFilterFactory.java`

```java
package com.quickbite.gateway.validation;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.List;

/** For action endpoints like POST /pickup: a body there is a client bug or a probe. */
@Component
public class NoBodyGatewayFilterFactory extends AbstractGatewayFilterFactory<NoBodyGatewayFilterFactory.Config> {
    public static class Config {}
    public NoBodyGatewayFilterFactory() { super(Config.class); }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            HttpHeaders h = exchange.getRequest().getHeaders();
            boolean hasBody = h.getContentLength() > 0 || h.getFirst(HttpHeaders.TRANSFER_ENCODING) != null;
            return hasBody ? Problems.write(exchange, 400, "This endpoint takes no request body", List.of())
                           : chain.filter(exchange);
        };
    }
}
```

### 5.7 Hardened routes: allow-listed paths and methods, validation per route

**Replace** the `routes:` block from Step 2 with the list below. Every public endpoint is now listed **explicitly**, with typed path variables and its methods. Anything else, such as `/api/orders/abc` or `DELETE /api/orders/1`, matches no route and gets `404`. The demo routes (file 16) are included; they are harmless when demo-svc isn't running.

```yaml
          routes:
            # ---------------- catalog ----------------
            - id: restaurants-list
              uri: ${CATALOG_URL:http://localhost:8081}
              predicates: [Path=/api/restaurants, Method=GET]
              filters: [ValidateQuery=restaurants-query]
            - id: restaurant-detail
              uri: ${CATALOG_URL:http://localhost:8081}
              predicates: ["Path=/api/restaurants/{id:[a-z0-9-]{1,32}}", Method=GET]
              filters: [ValidateQuery=no-query]
            - id: restaurant-menu-items
              uri: ${CATALOG_URL:http://localhost:8081}
              predicates: ["Path=/api/restaurants/{id:[a-z0-9-]{1,32}}/menu-items", Method=GET]
              filters: [ValidateQuery=menu-items-query]
            - id: restaurant-price-update
              uri: ${CATALOG_URL:http://localhost:8081}
              predicates: ["Path=/api/restaurants/{id:[a-z0-9-]{1,32}}/menu-items/{item:[a-z0-9-]{1,40}}/price", Method=PUT]
              filters: [ValidateQuery=no-query, ValidateJson=price-update]

            # ---------------- orders ----------------
            - id: orders-create
              uri: ${ORDER_URL:http://localhost:8082}
              predicates: [Path=/api/orders, Method=POST]
              filters: [ValidateQuery=no-query, ValidateJson=place-order]
              metadata: { response-timeout: 3000 }
            - id: orders-read
              uri: ${ORDER_URL:http://localhost:8082}
              predicates: ["Path=/api/orders,/api/orders/rider/active,/api/orders/{id:[0-9]{1,20}}", Method=GET]
              filters: [ValidateQuery=no-query]
            - id: orders-rider-actions
              uri: ${ORDER_URL:http://localhost:8082}
              predicates: ["Path=/api/orders/{id:[0-9]{1,20}}/pickup,/api/orders/{id:[0-9]{1,20}}/deliver", Method=POST]
              filters: [ValidateQuery=no-query, NoBody]
            - id: orders-admin-hang
              uri: ${ORDER_URL:http://localhost:8082}
              predicates: [Path=/api/orders/admin/debug/hang, Method=POST]
              filters: [ValidateQuery=hang-query, NoBody]

            # ---------------- payments ----------------
            - id: payment-methods-list
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: [Path=/api/payments/methods, Method=GET]
              filters: [ValidateQuery=no-query]
            - id: payment-methods-add
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: [Path=/api/payments/methods, Method=POST]
              filters:
                - ValidateQuery=no-query
                - name: ValidateJson
                  args: { schema: add-card, redact: true, maxBytes: 2048 }   # card data: never echo values
            - id: payment-method-default
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: ["Path=/api/payments/methods/{id:pm_[A-Za-z0-9_]{1,40}}/default", Method=POST]
              filters: [ValidateQuery=no-query, NoBody]
            - id: payment-method-delete
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: ["Path=/api/payments/methods/{id:pm_[A-Za-z0-9_]{1,40}}", Method=DELETE]
              filters: [ValidateQuery=no-query, NoBody]
            - id: payment-read
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: ["Path=/api/payments/orders/{id:[0-9]{1,20}},/api/payments/test-cards", Method=GET]
              filters: [ValidateQuery=no-query]
            - id: payment-admin-psp
              uri: ${PAYMENT_URL:http://localhost:8083}
              predicates: [Path=/api/payments/admin/psp, "Method=GET,POST"]
              filters: [ValidateQuery=no-query, ValidateJson=psp-config]          # body checked on POST only

            # ---------------- riders ----------------
            - id: rider-location
              uri: ${LOCATION_URL:http://localhost:8084}
              predicates: [Path=/api/riders/me/location, Method=POST]
              filters: [ValidateQuery=no-query, ValidateJson=rider-location]
            - id: rider-me
              uri: ${LOCATION_URL:http://localhost:8084}
              predicates: [Path=/api/riders/me, Method=GET]
              filters: [ValidateQuery=no-query]

            # ---------------- demo (file 16) ----------------
            - id: demo-status
              uri: ${DEMO_URL:http://localhost:8086}
              predicates: [Path=/api/demo/status, Method=GET]
              filters: [ValidateQuery=no-query]
            - id: demo-control
              uri: ${DEMO_URL:http://localhost:8086}
              predicates: ["Path=/api/demo/pause,/api/demo/resume", Method=POST]
              filters: [ValidateQuery=no-query, NoBody]
            - id: demo-speed
              uri: ${DEMO_URL:http://localhost:8086}
              predicates: [Path=/api/demo/speed, Method=POST]
              filters: [ValidateQuery=demo-speed-query, NoBody]

            # ---------------- websockets (path/origin checked by RequestSanityFilter) ----------------
            - id: rider-ws
              uri: ${LOCATION_WS_URL:ws://localhost:8084}
              predicates: [Path=/ws/rider, Method=GET]
            - id: updates-ws
              uri: ${REALTIME_WS_URL:ws://localhost:8085}
              predicates: [Path=/ws/updates, Method=GET]
```

> **What the gateway can't validate:** WebSocket *frames*. After the upgrade the gateway only pipes bytes, so location-svc validates every GPS message itself (range check and a 1/s throttle, file 08). Internal service-to-service calls also bypass the gateway, which is another reason services keep their own Bean Validation.

**Does anything in the guide break?** The existing clients were checked against these rules:

| Client | Result |
|---|---|
| Android app | UUID `X-Correlation-Id` and `Idempotency-Key` ✓; `paymentMethodId` null or omitted ✓; `POST /pickup` has an empty body ✓; no `Origin` on WebSockets ✓ |
| `rider-sim.sh`, `smoke.sh`, `e2e.sh`, k6 | Bodies match the schemas ✓. Only the file 11 "mass assignment" check changes: it's now `400` at the gateway instead of "ignored" (updated there) |
| demo-svc | `{"lat","lon"}` and empty-body pickup/deliver ✓ |
| order-svc → catalog `menu-items` | Internal call, doesn't pass the gateway (still protected by catalog's own validation) |

### 5.8 Unit tests

`services/gateway/src/test/java/com/quickbite/gateway/validation/RequestSanityFilterTest.java`

```java
package com.quickbite.gateway.validation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSanityFilterTest {
    final RequestSanityFilter filter = new RequestSanityFilter(
            new ValidationProps(0, 0, 0, 0, 0, 0, 0, null, null), new SimpleMeterRegistry());

    int status(MockServerHttpRequest req) {
        var ex = MockServerWebExchange.from(req);
        filter.filter(ex, e -> Mono.empty()).block();
        var s = ex.getResponse().getStatusCode();
        return s == null ? 200 : s.value();
    }

    @Test void pathTraversalAndEncodedTricks() {
        assertThat(status(MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/orders/%2e%2e/admin")).build())).isEqualTo(400);
        assertThat(status(MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api//orders")).build())).isEqualTo(400);
        assertThat(status(MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/orders;jsessionid=1")).build())).isEqualTo(400);
    }

    @Test void unknownMethod() {
        assertThat(status(MockServerHttpRequest.method(HttpMethod.valueOf("TRACE"), URI.create("/api/orders")).build())).isEqualTo(405);
    }

    @Test void queryPollutionAndCount() {
        assertThat(status(MockServerHttpRequest.get("/api/restaurants?city=blr&city=mum").build())).isEqualTo(400);
        var many = new StringBuilder("/api/restaurants?");
        for (int i = 0; i < 25; i++) many.append("p").append(i).append("=1&");
        assertThat(status(MockServerHttpRequest.method(HttpMethod.GET, URI.create(many.toString())).build())).isEqualTo(400);
    }

    @Test void bodyRules() {
        assertThat(status(MockServerHttpRequest.post("/api/orders").contentType(MediaType.TEXT_PLAIN).contentLength(2).body("hi"))).isEqualTo(415);
        assertThat(status(MockServerHttpRequest.post("/api/orders").contentType(MediaType.APPLICATION_JSON).contentLength(2).body("{}"))).isEqualTo(200);
        assertThat(status(MockServerHttpRequest.get("/api/orders").contentType(MediaType.APPLICATION_JSON).contentLength(2).body("{}"))).isEqualTo(400);
        assertThat(status(MockServerHttpRequest.post("/api/orders").header("Transfer-Encoding", "chunked").contentLength(2)
                .contentType(MediaType.APPLICATION_JSON).body("{}"))).isEqualTo(400);           // smuggling
        assertThat(status(MockServerHttpRequest.post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .contentLength(1_000_000).body("{}"))).isEqualTo(413);
    }

    @Test void authorizationShape() {
        assertThat(status(MockServerHttpRequest.get("/api/orders").header("Authorization", "Basic YWxpY2U6YWxpY2U=").build())).isEqualTo(401);
        assertThat(status(MockServerHttpRequest.get("/api/orders").header("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.").build()))
                .as("unsigned alg=none token").isEqualTo(401);
        assertThat(status(MockServerHttpRequest.get("/api/orders").header("Authorization", "Bearer aaa.bbb.ccc").build())).isEqualTo(200); // shape ok; signature checked later
    }

    @Test void idempotencyKeyFormat() {
        assertThat(status(MockServerHttpRequest.get("/api/orders").header("Idempotency-Key", "../../etc/passwd").build())).isEqualTo(400);
        assertThat(status(MockServerHttpRequest.get("/api/orders").header("Idempotency-Key", "6f1c1c9e-0d4a-4a53-9b0b-3d2f7e1d8a11").build())).isEqualTo(200);
    }

    @Test void invalidCorrelationIdIsDroppedNotRejected() {
        var seen = new AtomicReference<String>("unset");
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").header("X-Correlation-Id", "abc 2026-01-01 INFO forged").build());
        filter.filter(ex, e -> { seen.set(e.getRequest().getHeaders().getFirst("X-Correlation-Id")); return Mono.empty(); }).block();
        assertThat(seen.get()).isNull();
    }

    @Test void webSocketOriginAndPath() {
        assertThat(status(MockServerHttpRequest.get("/ws/updates").header("Upgrade", "websocket").header("Origin", "https://evil.example").build())).isEqualTo(403);
        assertThat(status(MockServerHttpRequest.get("/ws/updates").header("Upgrade", "websocket").build())).isEqualTo(200);        // native app
        assertThat(status(MockServerHttpRequest.get("/api/orders").header("Upgrade", "websocket").build())).isEqualTo(400);
    }
}
```

`services/gateway/src/test/java/com/quickbite/gateway/validation/JsonSchemaRegistryTest.java`

```java
package com.quickbite.gateway.validation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

class JsonSchemaRegistryTest {
    final JsonSchemaRegistry registry;
    JsonSchemaRegistryTest() throws Exception { registry = new JsonSchemaRegistry(); }

    java.util.List<String> order(String json) { return registry.validateJson("place-order", json.getBytes(StandardCharsets.UTF_8), false); }

    static final String VALID = """
        {"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":2}],"deliveryLat":12.9,"deliveryLon":77.6}""";

    @Test void validOrderPasses() { assertThat(order(VALID)).isEmpty(); }

    @Test void massAssignmentRejected() {
        assertThat(order(VALID.replace("\"quantity\":2", "\"quantity\":2,\"price\":1"))).isNotEmpty();
        assertThat(order(VALID.replace("{\"restaurantId\"", "{\"status\":\"DELIVERED\",\"restaurantId\""))).isNotEmpty();
    }

    @Test void rangesAndPatterns() {
        assertThat(order(VALID.replace("\"quantity\":2", "\"quantity\":21"))).anyMatch(m -> m.contains("quantity"));
        assertThat(order(VALID.replace("12.9", "91"))).anyMatch(m -> m.contains("deliveryLat"));
        assertThat(order(VALID.replace("\"r1\"", "\"R1'; DROP TABLE orders;--\""))).anyMatch(m -> m.contains("restaurantId"));
    }

    @Test void duplicateKeysRejected() {
        assertThat(order(VALID.replace("\"quantity\":2", "\"quantity\":1,\"quantity\":999"))).containsExactly("Duplicate JSON property");
    }

    @Test void parserLimits() {
        String deep = "{\"a\":".repeat(50) + "1" + "}".repeat(50);
        assertThat(order(deep)).containsExactly("JSON exceeds nesting or size limits");
        assertThat(order(VALID + " trailing")).containsExactly("Malformed JSON");
        assertThat(order("")).containsExactly("Request body is required");
    }

    @Test void messagesNeverEchoDangerousInput() {
        var errors = order(VALID.replace("\"quantity\":2", "\"quantity\":2,\"<script>alert(1)</script>\\r\\nINFO\":1"));
        assertThat(errors).isNotEmpty();
        assertThat(String.join(" ", errors)).doesNotContain("<", ">", "\r", "\n");
    }

    @Test void cardErrorsAreRedacted() {
        var errors = registry.validateJson("add-card",
                "{\"number\":\"4242424242424242\",\"expMonth\":13,\"expYear\":2030,\"cvc\":\"12a\"}".getBytes(StandardCharsets.UTF_8), true);
        assertThat(errors).contains("$.expMonth: invalid value", "$.cvc: invalid value");
        assertThat(String.join(" ", errors)).doesNotContain("4242").doesNotContain("12a");
    }

    /** Every schema referenced in application.yml must exist: catches typos in CI, not in production. */
    @Test void allReferencedSchemasExist() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        var m = Pattern.compile("(?:ValidateJson=|ValidateQuery=|schema:\\s*)([a-z0-9-]+)").matcher(yml);
        int found = 0;
        while (m.find()) { registry.requireSchema(m.group(1)); found++; }
        assertThat(found).isGreaterThan(10);
    }
}
```

```bash
./gradlew :services:gateway:test
```

### 5.9 Verify: attack the gateway

Run these against the gateway (8080) directly, or through NGINX (8000), which applies its own limits first.

```bash
T=$(scripts/token.sh alice alice); A="Authorization: Bearer $T"; J='Content-Type: application/json'
c() { curl -s -o /dev/null -w "%{http_code}  " "$@"; echo; }
ORDER='{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}'

c --path-as-is "localhost:8080/api/orders/../internal/riders/nearest" -H "$A"        # 400 traversal
c "localhost:8080/api/orders/%2e%2e/x" -H "$A"                                       # 400 encoded traversal
c -X TRACE localhost:8080/api/orders                                                 # 405
c "localhost:8080/api/restaurants?city=blr&city=mum"                                 # 400 parameter pollution
c -G --data-urlencode "city=<script>" localhost:8080/api/restaurants                 # 400 schema (pattern)
c "localhost:8080/api/restaurants/r1?debug=true"                                     # 400 unexpected query param
c "localhost:8080/api/orders/abc" -H "$A"                                            # 404 id must be numeric (no route)
c -X DELETE "localhost:8080/api/orders/123" -H "$A"                                  # 404 method not routed
c -X POST localhost:8080/api/orders -H "$A" -H 'Content-Type: text/plain' -d 'hi'    # 415
c -X POST localhost:8080/api/orders -H "Authorization: Bearer not-a-jwt" -H "$J" -d "$ORDER"   # 401 before any JWT parsing
c -X POST localhost:8080/api/orders -H "$A" -H "$J" -H "Idempotency-Key: ../../etc" -d "$ORDER"  # 400
c -X POST localhost:8080/api/orders/123/pickup -H "$A" -H "$J" -d '{"x":1}'          # 400 NoBody

# Schema errors come back as problem+json with field-level messages:
curl -s -X POST localhost:8080/api/orders -H "$A" -H "$J" \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":99,"price":1}],"deliveryLat":12.9,"deliveryLon":77.6}' | jq
# Duplicate key smuggling:
curl -s -X POST localhost:8080/api/orders -H "$A" -H "$J" \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1,"quantity":999}],"deliveryLat":12.9,"deliveryLon":77.6}' | jq .errors
# Deep nesting:
python3 -c 'print("{\"a\":"*100 + "1" + "}"*100)' | curl -s -X POST localhost:8080/api/orders -H "$A" -H "$J" --data-binary @- | jq .errors
# Card errors are redacted (no values echoed):
curl -s -X POST localhost:8080/api/payments/methods -H "$A" -H "$J" \
  -d '{"number":"4242424242424242","expMonth":13,"expYear":2030,"cvc":"12a"}' | jq .errors
# Header flood:
curl -s -o /dev/null -w "%{http_code}\n" $(for i in $(seq 1 70); do printf -- "-H X-Junk-%s:1 " $i; done) localhost:8080/api/restaurants   # 431
# Cross-site WebSocket hijacking attempt:
websocat -H "Origin: https://evil.example" -H "$A" ws://localhost:8080/ws/updates     # rejected (403)

# Regression: a valid order still works
c -X POST localhost:8080/api/orders -H "$A" -H "$J" -H "Idempotency-Key: $(uuidgen)" -d "$ORDER"   # 201
```

| Check | Pass condition |
|---|---|
| Traversal, encoding, pollution, unknown params | `400` |
| Wrong type or path | `404` (no route): the allow-list works |
| `TRACE` / `text/plain` / junk `Authorization` | `405` / `415` / `401` |
| Schema errors | `400` with `errors` naming `$.items[0].quantity` and the unknown `price` |
| Duplicate keys / deep nesting | `"Duplicate JSON property"` / `"JSON exceeds nesting or size limits"` |
| Card errors | Paths only (`$.cvc: invalid value`), no card digits in the response |
| 70 junk headers | `431` |
| Valid order | `201` |

Every rejection increments the `gateway.validation.rejected{reason=...}` counter. Graph it in Grafana (file 12): a sudden spike of one reason from one client is a scanner probing you.

**Checkpoint:**

1. Why does `RequestSanityFilter` run *before* Spring Security, while `ValidateJson` runs *after* authentication?
2. Give a concrete exploit that duplicate-key detection prevents in a gateway + service architecture.
3. Why are invalid correlation ids dropped while invalid idempotency keys are rejected?
