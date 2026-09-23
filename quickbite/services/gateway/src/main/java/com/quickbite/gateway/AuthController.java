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
    @SuppressWarnings("null")
    @GetMapping("/session")
    public Mono<Map<Object, Object>> session(@AuthenticationPrincipal Jwt jwt) {
        String sid = SessionGlobalFilter.sessionIdOf(jwt);
        return redis.opsForHash().entries("session:" + sid).collectMap(Map.Entry::getKey, Map.Entry::getValue)
            .map(m -> { m.put("sid", sid); return m; });
    }
}