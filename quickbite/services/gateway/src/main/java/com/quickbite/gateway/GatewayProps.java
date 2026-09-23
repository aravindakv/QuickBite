package com.quickbite.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("quickbite")
public record GatewayProps(Security security, Session session) {
    public record Security(String issuer, String jwkSetUri, String audience) {}
    public record Session(Duration revokeTtl, Duration idleTtl) {}
}