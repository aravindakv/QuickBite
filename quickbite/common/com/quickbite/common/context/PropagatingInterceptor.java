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