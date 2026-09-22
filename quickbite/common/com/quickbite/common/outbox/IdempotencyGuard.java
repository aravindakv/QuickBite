package com.quickbite.common.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;

public class IdempotencyGuard {
    private final JdbcClient jdbc;
    public IdempotencyGuard(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** true the first time an event id is seen. Call INSIDE the business transaction: if it rolls back, so does the marker. */
    public boolean firstTime(String eventId) {
        return jdbc.sql("insert into processed_events(event_id) values (?) on conflict do nothing")
                .param(eventId).update() == 1;
    }
}