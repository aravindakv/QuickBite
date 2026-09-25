# 02 — Monorepo, Gradle Convention Plugins, and the Shared Library

**Goal:** a Gradle build where every service gets the same Java version, BOMs, and test setup for free, plus a `libs/common` library that gives every service four things automatically:

- session-id / correlation-id propagation
- JWT security
- a process watchdog
- the transactional outbox

---

## Step 1: Version catalog

`gradle/libs.versions.toml`

```toml
[versions]
springBoot = "4.0.3"        # bump to the latest 4.0.x patch from start.spring.io
springCloud = "2025.1.0"    # Spring Cloud "Oakwood" = the train built for Boot 4.0
depMgmt = "1.1.7"
archunit = "1.4.1"
```

**Why a catalog:** one place to bump versions. Spring's BOMs then pin every transitive library (Jackson, Kafka client, Hibernate…) to versions tested together. You almost never write a version number in a service build file.

---

## Step 2: Convention plugins (`build-logic`)

**Why:** without these, six `build.gradle.kts` files drift apart ("why is payment-svc on a different JUnit?"). Convention plugins are Gradle's answer: shared build logic as code.

`build-logic/settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`

```kotlin
plugins { `kotlin-dsl` }

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${libs.versions.springBoot.get()}")
    implementation("io.spring.gradle:dependency-management-plugin:${libs.versions.depMgmt.get()}")
}
```

`build-logic/src/main/kotlin/quickbite.java-conventions.gradle.kts`

```kotlin
plugins { java }

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

tasks.withType<JavaCompile>().configureEach {
    // -parameters lets Spring bind @PathVariable/@RequestParam by name without annotations' value
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading") // silences Mockito's agent warning on modern JDKs
}
```

`build-logic/src/main/kotlin/quickbite.library.gradle.kts`

```kotlin
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("quickbite.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<DependencyManagementExtension> {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${catalog.findVersion("springCloud").get().requiredVersion}")
    }
}
```

`build-logic/src/main/kotlin/quickbite.spring-service.gradle.kts`

```kotlin
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("quickbite.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${catalog.findVersion("springCloud").get().requiredVersion}")
    }
}

dependencies {
    // Logs a warning if a property key we use was renamed in Boot 4. Remove once the logs are clean.
    runtimeOnly("org.springframework.boot:spring-boot-properties-migrator")
}

// Every service produces build/libs/app.jar -> one generic Dockerfile works for all (file 09)
tasks.named<BootJar>("bootJar") { archiveFileName.set("app.jar") }
tasks.named<Jar>("jar") { enabled = false }
```

Root `settings.gradle.kts`

```kotlin
pluginManagement { includeBuild("build-logic") }

rootProject.name = "quickbite"

include(
    "libs:common",
    "services:gateway",
    "services:catalog-svc",
    "services:order-svc",
    "services:payment-svc",
    "services:location-svc",
    "services:realtime-svc",
)
```

**Verify now:** `./gradlew projects` should list all modules. They are empty for now, which is fine.

---

## Step 3: The shared library — design first

`libs/common` is a **Spring Boot auto-configuration library**. When a service adds `implementation(project(":libs:common"))`, it automatically gets these features:

| Feature | Class | Concept you learn |
|---|---|---|
| Session-id and correlation-id in every log line, forwarded on every outbound HTTP call and Kafka event | `CorrelationFilter`, `PropagatingInterceptor`, `KafkaMdcInterceptor` | Distributed context propagation |
| JWT resource-server security, with Keycloak roles mapped to Spring roles | `QuickbiteSecurityAutoConfiguration` | Zero trust: every service validates tokens, not only the gateway |
| Client-credentials tokens for service-to-service calls | `ServiceTokenProvider` | OAuth2 machine-to-machine flow |
| Process watchdog wired into the liveness probe | `LoopWatchdog` | Detecting hung-but-alive processes |
| Transactional outbox and idempotent consumers | `OutboxWriter`, `OutboxRelay`, `IdempotencyGuard` | Reliable event publishing, at-least-once delivery |
| Dead-letter topics for poison messages | `KafkaErrorHandlingAutoConfiguration` | Error isolation |
| 64-bit sortable IDs | `SnowflakeIdGenerator` | Coordination-free ID generation |

> **Why the gateway does NOT use this library:** the gateway is reactive (WebFlux + Netty), while the services are servlet-based (Spring MVC on virtual threads). Security and filter APIs differ between the two stacks. This is a real-world constraint worth noticing.

`libs/common/build.gradle.kts`

```kotlin
plugins { id("quickbite.library") }

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")

    // Optional features: compiled against, but only activated if the service has them on its classpath
    compileOnly("org.springframework.boot:spring-boot-starter-kafka")
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

> If Gradle cannot resolve `spring-boot-starter-kafka` in your Boot patch, replace it with `org.springframework.kafka:spring-kafka` everywhere in this guide. The code is identical.

All Java below lives under `libs/common/src/main/java/com/quickbite/common/`.

---

## Step 4: Context propagation (session-id + correlation-id)

**Concept:**

- The gateway (file 04) validates the JWT, reads its `sid` claim (the Keycloak session id), and sets `X-Session-Id`. It also sets `X-Correlation-Id` (one id per user action).
- Every service puts both into the logging **MDC**, so every log line carries them.
- Every outbound HTTP call and Kafka message copies them forward.
- One `grep sid=abc` across all services then reconstructs a user's entire journey.

`context/Headers.java`

```java
package com.quickbite.common.context;

public final class Headers {
    public static final String SESSION_ID = "X-Session-Id";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String USER_ID = "X-User-Id";
    // Kafka header names
    public static final String K_EVENT_ID = "eventId";
    public static final String K_EVENT_TYPE = "eventType";
    public static final String K_SESSION_ID = "sessionId";
    public static final String K_CORRELATION_ID = "correlationId";
    // MDC keys
    public static final String MDC_SESSION = "sessionId";
    public static final String MDC_CORRELATION = "correlationId";
    private Headers() {}
}
```

`context/CorrelationFilter.java`

```java
package com.quickbite.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Runs first on every request: puts session/correlation ids into the MDC for the lifetime of the request. */
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String sid = req.getHeader(Headers.SESSION_ID);
        String cid = req.getHeader(Headers.CORRELATION_ID);
        if (cid == null || cid.isBlank()) cid = UUID.randomUUID().toString();
        MDC.put(Headers.MDC_SESSION, sid == null ? "-" : sid);
        MDC.put(Headers.MDC_CORRELATION, cid);
        res.setHeader(Headers.CORRELATION_ID, cid); // lets the client quote it in bug reports
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(Headers.MDC_SESSION);
            MDC.remove(Headers.MDC_CORRELATION);
        }
    }
}
```

`context/PropagatingInterceptor.java`

```java
package com.quickbite.common.context;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** Add to every RestClient so downstream services see the same ids. */
public class PropagatingInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution exec)
            throws IOException {
        String sid = MDC.get(Headers.MDC_SESSION);
        String cid = MDC.get(Headers.MDC_CORRELATION);
        if (sid != null && !"-".equals(sid)) request.getHeaders().set(Headers.SESSION_ID, sid);
        if (cid != null) request.getHeaders().set(Headers.CORRELATION_ID, cid);
        return exec.execute(request, body);
    }
}
```

`context/ContextAutoConfiguration.java`

```java
package com.quickbite.common.context;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
public class ContextAutoConfiguration {
    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        var reg = new FilterRegistrationBean<>(new CorrelationFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE); // before Spring Security, so auth failures are logged with ids too
        return reg;
    }
}
```

> If `FilterRegistrationBean` is not found in your Boot patch, check its new package with your IDE's auto-import. Boot 4 moved several servlet classes into the `spring-boot-servlet` module.

---

## Step 5: Security (every service is a resource server)

**Concept:**

- The gateway checks tokens, but services check them **again**. If an attacker ever reaches a service directly (a misconfigured network policy, or a compromised neighbour pod), an unauthenticated request must still fail. This is called *zero trust* or *defense in depth*.
- JWT validation is local and cheap: verify an RSA signature against keys cached from Keycloak's JWKS endpoint, then check `iss`, `exp` and `aud`. No network call happens per request.

`security/SecurityProps.java`

```java
package com.quickbite.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties("quickbite.security")
public record SecurityProps(String issuer, String jwkSetUri, String audience, List<String> publicPaths) {
    public SecurityProps {
        publicPaths = publicPaths == null ? List.of() : publicPaths; // format "GET:/api/restaurants/**"
    }
}
```

`security/QuickbiteSecurityAutoConfiguration.java`

```java
package com.quickbite.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@EnableMethodSecurity // enables @PreAuthorize("hasRole('rider')") on controller methods
@EnableConfigurationProperties(SecurityProps.class)
public class QuickbiteSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProps props,
                                    JwtAuthenticationConverter converter) throws Exception {
        http.csrf(c -> c.disable()) // stateless bearer-token API: no cookies, so no CSRF risk
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                for (String p : props.publicPaths()) {
                    String[] parts = p.split(":", 2);
                    a.requestMatchers(HttpMethod.valueOf(parts[0]), parts[1]).permitAll();
                }
                a.anyRequest().authenticated();
            })
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /** Signing keys from the INTERNAL url, but issuer must equal the PUBLIC url (see file 01, step 3). */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(SecurityProps p) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(p.jwkSetUri()).build();
        var audience = new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(p.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(p.issuer()), audience));
        return decoder;
    }

    /** Keycloak puts realm roles in realm_access.roles -> map them to ROLE_customer, ROLE_rider, ... */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            @SuppressWarnings("unchecked")
            Collection<String> roles = realmAccess == null ? List.of()
                    : (Collection<String>) realmAccess.getOrDefault("roles", List.of());
            return roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        });
        return converter;
    }
}
```

### Service-to-service tokens (client credentials)

**Concept:** when order-svc calls location-svc from a background job, there is no user. The service logs in *as itself*:

- It POSTs `grant_type=client_credentials` with its client id and secret to Keycloak and receives a token for a service-account user holding the `service` role.
- The token is cached until 30 s before it expires, so it costs roughly one Keycloak call per 5 minutes.

We write this by hand (about 40 lines) so you can see exactly what OAuth2 client libraries do for you.

`security/ServiceTokenProvider.java`

```java
package com.quickbite.common.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

public class ServiceTokenProvider {
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") long expiresIn) {}
    private record Cached(String value, Instant expiresAt) {}

    private final RestClient http = RestClient.create();
    private final String tokenUri, clientId, clientSecret;
    private volatile Cached cached;

    public ServiceTokenProvider(String tokenUri, String clientId, String clientSecret) {
        this.tokenUri = tokenUri; this.clientId = clientId; this.clientSecret = clientSecret;
    }

    public String token() {
        Cached c = cached;
        if (c != null && c.expiresAt().isAfter(Instant.now().plusSeconds(30))) return c.value();
        synchronized (this) { // double-checked: only one thread refreshes
            c = cached;
            if (c != null && c.expiresAt().isAfter(Instant.now().plusSeconds(30))) return c.value();
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            TokenResponse r = http.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            cached = new Cached(r.accessToken(), Instant.now().plusSeconds(r.expiresIn()));
            return r.accessToken();
        }
    }
}
```

`security/ServiceAuthInterceptor.java`

```java
package com.quickbite.common.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.*;
import java.io.IOException;

public class ServiceAuthInterceptor implements ClientHttpRequestInterceptor {
    private final ServiceTokenProvider tokens;
    public ServiceAuthInterceptor(ServiceTokenProvider tokens) { this.tokens = tokens; }

    @Override
    public ClientHttpResponse intercept(HttpRequest req, byte[] body, ClientHttpRequestExecution exec) throws IOException {
        req.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token());
        return exec.execute(req, body);
    }
}
```

---

## Step 6: The process watchdog

**Concept:** Android's `system_server` Watchdog checks that critical Handler threads keep processing. If one blocks for too long, it kills the process so init restarts it. We do the same:

- Each long-running loop (outbox relay, dispatcher, Kafka listeners) calls `beat("name")` on every iteration.
- If any loop is silent for more than 30 s, or the JVM detects deadlocked threads, the **liveness** probe reports DOWN.
- Kubernetes then restarts the container. A process that is alive but stuck is worse than a dead one, because nothing restarts it.

`health/LoopWatchdog.java`

```java
package com.quickbite.common.health;

import org.springframework.boot.health.contributor.Health;          // Boot 3.x: org.springframework.boot.actuate.health
import org.springframework.boot.health.contributor.HealthIndicator;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LoopWatchdog implements HealthIndicator {
    private final Map<String, AtomicLong> beats = new ConcurrentHashMap<>();
    private final long timeoutNanos;

    public LoopWatchdog(Duration timeout) { this.timeoutNanos = timeout.toNanos(); }

    public void beat(String loop) {
        beats.computeIfAbsent(loop, k -> new AtomicLong()).set(System.nanoTime());
    }

    @Override
    public Health health() {
        long now = System.nanoTime();
        var stuck = beats.entrySet().stream()
                .filter(e -> now - e.getValue().get() > timeoutNanos)
                .map(Map.Entry::getKey).toList();
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (stuck.isEmpty() && deadlocked == null) {
            return Health.up().withDetail("loops", beats.keySet()).build();
        }
        return Health.down()
                .withDetail("stuckLoops", stuck)
                .withDetail("deadlockedThreads", deadlocked == null ? 0 : deadlocked.length)
                .build();
    }
}
```

`health/WatchdogAutoConfiguration.java`

```java
package com.quickbite.common.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import java.time.Duration;

@AutoConfiguration
public class WatchdogAutoConfiguration {
    @Bean("loopWatchdog") // bean name = health component name used in the liveness group
    LoopWatchdog loopWatchdog(@Value("${quickbite.watchdog.timeout:30s}") Duration timeout) {
        return new LoopWatchdog(timeout);
    }
}
```

> **Critical rule:** only put *the process's own health* in liveness. Never put the database in liveness. If Postgres blips for 20 s, readiness correctly takes pods out of rotation. If Postgres were in liveness, Kubernetes would restart **every pod at once** and turn a blip into an outage.

---

## Step 7: Snowflake IDs

**Concept:** database sequences need a round-trip and don't work across shards. UUIDs are random, so they fragment B-tree indexes. A Snowflake ID packs three fields into 64 bits:

- `41 bits of milliseconds since 2026-01-01`
- `10 bits of node id`
- `12 bits of sequence`

The result is unique across up to 1024 nodes, **time-sortable** (index-friendly), and generated in about 50 ns with no coordination.

`id/SnowflakeIdGenerator.java`

```java
package com.quickbite.common.id;

public final class SnowflakeIdGenerator {
    private static final long EPOCH = 1767225600000L; // 2026-01-01T00:00:00Z
    private final long nodeId;
    private long lastTs = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > 1023) throw new IllegalArgumentException("nodeId must be 0..1023");
        this.nodeId = nodeId;
    }

    public synchronized long nextId() {
        long ts = System.currentTimeMillis();
        if (ts < lastTs) ts = lastTs;              // clock went backwards (NTP): reuse last ms, never go back
        if (ts == lastTs) {
            sequence = (sequence + 1) & 0xFFF;     // 4096 ids per ms per node
            if (sequence == 0) {                   // exhausted this ms: spin to the next one
                while ((ts = System.currentTimeMillis()) <= lastTs) Thread.onSpinWait();
            }
        } else {
            sequence = 0;
        }
        lastTs = ts;
        return ((ts - EPOCH) << 22) | (nodeId << 12) | sequence;
    }
}
```

> **Gotcha you'll meet in file 10:** JavaScript numbers are exact only up to 2^53, and Snowflake IDs exceed that. APIs therefore return IDs **as strings**. Kotlin `Long` would be fine, but web clients would silently corrupt the IDs.

---

## Step 8: Outbox and idempotency

**The problem (the dual-write bug):** order-svc must (1) save the order in Postgres and (2) publish `order.created` to Kafka. Two systems means there is no shared transaction:

- If it crashes between the two steps, you either have an order nobody pays for, or an event for an order that doesn't exist.

**The fix (transactional outbox):**

1. Write the event into an `outbox` table **in the same DB transaction** as the order. Both commit, or neither does.
2. A relay loop reads unpublished rows and sends them to Kafka.
3. The relay marks each row published after the send succeeds.

This gives **at-least-once** delivery. If the relay crashes after sending but before marking the row, the event is sent again. So every consumer must be **idempotent**: it records processed event ids and skips duplicates.

`outbox/OutboxWriter.java`

```java
package com.quickbite.common.outbox;

import com.quickbite.common.context.Headers;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper; // Jackson 3 (Boot 4). Exceptions are unchecked.

import java.util.UUID;

public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    public void write(String topic, String key, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must run inside the business transaction");
        }
        jdbc.sql("""
                insert into outbox(id, topic, msg_key, event_type, payload, session_id, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
            .params(UUID.randomUUID(), topic, key, eventType, json.writeValueAsString(payload),
                    MDC.get(Headers.MDC_SESSION), MDC.get(Headers.MDC_CORRELATION))
            .update();
    }
}
```

`outbox/OutboxRelay.java`

```java
package com.quickbite.common.outbox;

import com.quickbite.common.context.Headers;
import com.quickbite.common.health.LoopWatchdog;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class OutboxRelay {
    record Row(UUID id, String topic, String msgKey, String eventType, String payload,
               String sessionId, String correlationId, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final LoopWatchdog watchdog;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, LoopWatchdog watchdog) {
        this.jdbc = jdbc; this.kafka = kafka; this.tx = tx; this.watchdog = watchdog;
    }

    @Scheduled(fixedDelayString = "${quickbite.outbox.poll-ms:500}")
    public void publishBatch() {
        watchdog.beat("outbox-relay");
        tx.executeWithoutResult(status -> {
            // SKIP LOCKED: several replicas can run the relay without sending the same row twice concurrently
            var rows = jdbc.sql("""
                    select id, topic, msg_key, event_type, payload, session_id, correlation_id, created_at
                    from outbox where published_at is null
                    order by created_at limit 100 for update skip locked""")
                .query(Row.class).list();
            for (Row r : rows) {
                var rec = new ProducerRecord<String, String>(r.topic(), r.msgKey(), r.payload());
                header(rec, Headers.K_EVENT_ID, r.id().toString());
                header(rec, Headers.K_EVENT_TYPE, r.eventType());
                header(rec, Headers.K_SESSION_ID, r.sessionId());
                header(rec, Headers.K_CORRELATION_ID, r.correlationId());
                try {
                    kafka.send(rec).get(5, TimeUnit.SECONDS); // wait for broker ack (acks=all)
                } catch (Exception e) {
                    throw new IllegalStateException("Kafka send failed; batch rolls back and retries", e);
                }
                jdbc.sql("update outbox set published_at = now() where id = ?").param(r.id()).update();
            }
        });
    }

    private static void header(ProducerRecord<String, String> rec, String name, String value) {
        if (value != null) rec.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
```

`outbox/IdempotencyGuard.java`

```java
package com.quickbite.common.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;

public class IdempotencyGuard {
    private final JdbcClient jdbc;
    public IdempotencyGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** true the first time an event id is seen. Call INSIDE the business transaction: if it rolls back, so does the marker. */
    public boolean firstTime(String eventId) {
        return jdbc.sql("insert into processed_events(event_id) values (?) on conflict do nothing")
                .param(eventId).update() == 1;
    }
}
```

`outbox/OutboxAutoConfiguration.java`

```java
package com.quickbite.common.outbox;

import com.quickbite.common.health.LoopWatchdog;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@EnableScheduling
@ConditionalOnProperty(name = "quickbite.outbox.enabled", havingValue = "true")
public class OutboxAutoConfiguration {
    @Bean OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) { return new OutboxWriter(jdbc, json); }
    @Bean IdempotencyGuard idempotencyGuard(JdbcClient jdbc) { return new IdempotencyGuard(jdbc); }
    @Bean OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
                                  TransactionTemplate tx, LoopWatchdog watchdog) {
        return new OutboxRelay(jdbc, kafka, tx, watchdog);
    }
}
```

**Each service that uses the outbox** needs these two tables in its Flyway migration (files 06 and 07 include them):

```sql
create table outbox (
  id uuid primary key,
  topic varchar(200) not null,
  msg_key varchar(200) not null,
  event_type varchar(100) not null,
  payload text not null,
  session_id varchar(100),
  correlation_id varchar(100),
  created_at timestamptz not null default now(),
  published_at timestamptz
);
create index idx_outbox_unpublished on outbox(created_at) where published_at is null;

create table processed_events (
  event_id varchar(64) primary key,
  processed_at timestamptz not null default now()
);
```

---

## Step 9: Kafka consumer plumbing (MDC and dead letters)

**Concepts:**

- **MDC for consumers:** a Kafka listener thread has no HTTP request, so we restore session and correlation ids from the message headers.
- **Dead-letter topic (DLT):** a message that fails deterministically (a *poison pill*, e.g. malformed JSON) would otherwise block its partition forever. After 3 retries it moves to `<topic>.dlt` for a human to inspect, and the partition keeps flowing.

`kafka/KafkaErrorHandlingAutoConfiguration.java`

```java
package com.quickbite.common.kafka;

import com.quickbite.common.context.Headers;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class KafkaErrorHandlingAutoConfiguration {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + ".dlt", rec.partition()));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        // Bad JSON will never succeed on retry -> straight to DLT
        handler.addNotRetryableExceptions(tools.jackson.core.JacksonException.class, IllegalArgumentException.class);
        return handler;
    }

    @Bean
    RecordInterceptor<Object, Object> mdcRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> r, Consumer<Object, Object> c) {
                MDC.put(Headers.MDC_SESSION, header(r, Headers.K_SESSION_ID));
                MDC.put(Headers.MDC_CORRELATION, header(r, Headers.K_CORRELATION_ID));
                return r;
            }
            @Override
            public void afterRecord(ConsumerRecord<Object, Object> r, Consumer<Object, Object> c) {
                MDC.remove(Headers.MDC_SESSION);
                MDC.remove(Headers.MDC_CORRELATION);
            }
        };
    }

    public static String header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? "-" : new String(h.value(), StandardCharsets.UTF_8);
    }
}
```

> Every topic `X` that a service consumes needs a matching `X.dlt` topic with **the same partition count**. Each consumer service declares both as `NewTopic` beans (files 06–08).

---

## Step 10: Error responses

`web/ApiExceptionHandler.java`

```java
package com.quickbite.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.NoSuchElementException;

/** RFC 9457 problem+json responses: clients get a consistent error shape from every service. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()); }
}
```

`web/WebAutoConfiguration.java`

```java
package com.quickbite.common.web;

import com.quickbite.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import(ApiExceptionHandler.class)
public class WebAutoConfiguration {
    /** NODE_ID must be unique per running instance. Compose sets it; the hash fallback is OK for local dev.
     *  Services can replace this bean (file 12 does, using a DB sequence) -> @ConditionalOnMissingBean. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    SnowflakeIdGenerator snowflakeIdGenerator(@Value("${NODE_ID:#{null}}") Integer nodeId) {
        int id = nodeId != null ? nodeId : Math.floorMod(System.getenv().getOrDefault("HOSTNAME", "local").hashCode(), 1024);
        return new SnowflakeIdGenerator(id);
    }
}
```

---

## Step 11: Register the auto-configurations and ship shared defaults

`libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.quickbite.common.context.ContextAutoConfiguration
com.quickbite.common.security.QuickbiteSecurityAutoConfiguration
com.quickbite.common.health.WatchdogAutoConfiguration
com.quickbite.common.web.WebAutoConfiguration
com.quickbite.common.outbox.OutboxAutoConfiguration
com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration
```

`libs/common/src/main/resources/quickbite-defaults.yml`: every service imports this file.

```yaml
spring:
  data:
    redis:
      # A cache MUST fail faster than the store it fronts. Lettuce's default command timeout is 60 s,
      # so without these an unreachable Redis turns every request into a 60 s hang instead of a fast
      # fallback to the database. (Each service still sets its own spring.data.redis.host.)
      timeout: 300ms           # command timeout
      connect-timeout: 300ms
      lettuce:
        shutdown-timeout: 100ms
  threads:
    virtual:
      enabled: true            # blocking I/O on virtual threads: thread-per-request without the thread cost
  lifecycle:
    timeout-per-shutdown-phase: 20s
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      acks: all                # leader + in-sync replicas must persist before ack
      properties:
        enable.idempotence: true   # broker de-dupes producer retries
        linger.ms: 5               # wait up to 5 ms to batch -> far fewer requests
        compression.type: zstd
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: record         # commit offset after each record is processed successfully

server:
  shutdown: graceful           # finish in-flight requests on SIGTERM

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true          # /actuator/health/liveness and /readiness even outside Kubernetes
      show-details: always     # local learning only; hide in production
      group:
        liveness:
          include: livenessState,loopWatchdog

logging:
  pattern:
    level: "%5p [sid=%X{sessionId:-} cid=%X{correlationId:-}]"

quickbite:
  security:
    issuer: ${KC_ISSUER:http://localhost:8180/realms/quickbite}
    jwk-set-uri: ${KC_JWKS:http://localhost:8180/realms/quickbite/protocol/openid-connect/certs}
    audience: quickbite-api
  service-auth:
    token-uri: ${KC_TOKEN_URI:http://localhost:8180/realms/quickbite/protocol/openid-connect/token}
```

Each service's `application.yml` starts with:

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
```

> **Precedence rule:** values from an imported file **override** the importing file. So never repeat a key that already exists in `quickbite-defaults.yml` inside a service's `application.yml`; override with environment variables instead. Keep the two files' keys disjoint.

---

## Verify

```bash
./gradlew :libs:common:build
```

The build should succeed with zero compile errors. If an import fails, it is almost always a Boot 4 package move. Let your IDE re-resolve the import, and look the class up in the Spring Boot 4.0 Migration Guide.

**Checkpoint questions (answer them in your notes):**

1. Why does `OutboxWriter` refuse to run outside a transaction?
2. Why is `IdempotencyGuard.firstTime` called inside the same transaction as the business update, not before it?
3. What happens to a partition if a poison message had no DLT?
