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