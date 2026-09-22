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