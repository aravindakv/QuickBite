package com.quickbite.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties("quickbite.security")
public record SecurityProps(String issuer, String jwkSetUri, String audience, List<String> publicPaths) {
    public SecurityProps {
        publicPaths = publicPaths == null ? List.of() : publicPaths; // format "GET:/api/restaurants/**"
    }
}