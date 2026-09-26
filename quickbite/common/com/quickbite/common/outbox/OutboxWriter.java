package com.quickbite.common.outbox;

import com.quickbite.common.context.RequestContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

public class OutboxWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    /** Preferred: the caller states the context, so no ThreadLocal has to survive the call chain. */
    public void write(RequestContext ctx, String topic, String key, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox writes must run inside the business transaction");
        }
        jdbc.sql("""
                insert into outbox(id, topic, msg_key, event_type, payload, session_id, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
            .params(UUID.randomUUID(), topic, key, eventType, json.writeValueAsString(payload),
                    ctx.sessionId(), ctx.correlationId())
            .update();
    }

    /** Legacy call sites: falls back to the MDC (exactly what proved unreliable). Migrate away. */
    @Deprecated
    public void write(String topic, String key, String eventType, Object payload) {
        write(RequestContext.fromMdc(), topic, key, eventType, payload);
    }
}