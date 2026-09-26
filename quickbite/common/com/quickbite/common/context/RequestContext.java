package com.quickbite.common.context;

import org.slf4j.MDC;

/**
 * The ids that belong to one user action. Captured where they are certain (a controller or a Kafka
 * listener) and passed down explicitly, so no layer depends on a ThreadLocal surviving the call chain.
 */
public record RequestContext(String sessionId, String correlationId) {

    public static final RequestContext NONE = new RequestContext(null, null);

    public static RequestContext of(String sessionId, String correlationId) {
        return new RequestContext(blankToNull(sessionId), blankToNull(correlationId));
    }

    /** Best effort, for call sites still running on the request thread. Background jobs get NONE. */
    public static RequestContext fromMdc() {
        return of(MDC.get(Headers.MDC_SESSION), MDC.get(Headers.MDC_CORRELATION));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() || "-".equals(v) ? null : v;
    }
}