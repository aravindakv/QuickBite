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

    @SuppressWarnings("null")
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