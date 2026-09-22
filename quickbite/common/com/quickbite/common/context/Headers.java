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