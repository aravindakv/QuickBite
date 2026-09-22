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