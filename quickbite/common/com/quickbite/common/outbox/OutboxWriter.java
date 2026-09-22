package com.quickbite.common.outbox;

import com.quickbite.common.context.Headers;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper; // Jackson 3 (Boot 4). Exceptions are unchecked.

import java.util.UUID;

public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    public void write(String topic, String key, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must run inside the business transaction");
        }
        jdbc.sql("""
                insert into outbox(id, topic, msg_key, event_type, payload, session_id, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
            .params(UUID.randomUUID(), topic, key, eventType, json.writeValueAsString(payload),
                    MDC.get(Headers.MDC_SESSION), MDC.get(Headers.MDC_CORRELATION))
            .update();
    }
}