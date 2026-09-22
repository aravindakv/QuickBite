package com.quickbite.common.outbox;

import com.quickbite.common.context.Headers;
import com.quickbite.common.health.LoopWatchdog;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/** Polls the outbox table and republishes rows to Kafka. At-least-once: a crash between
 *  publish and delete redelivers, so consumers must dedupe with {@link IdempotencyGuard}. */
public class OutboxRelay {
    private static final int BATCH_SIZE = 100;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final LoopWatchdog watchdog;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
                        TransactionTemplate tx, LoopWatchdog watchdog) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.watchdog = watchdog;
    }

    @Scheduled(fixedDelayString = "${quickbite.outbox.poll-interval:1000}")
    void relay() {
        watchdog.beat("outbox-relay");
        tx.executeWithoutResult(status -> {
            List<Row> rows = jdbc.sql("""
                    select id, topic, msg_key, event_type, payload, session_id, correlation_id
                    from outbox
                    order by id
                    limit ?
                    for update skip locked""")
                .param(BATCH_SIZE)
                .query(Row.class)
                .list();

            for (Row row : rows) {
                publish(row);
                jdbc.sql("delete from outbox where id = ?").param(row.id()).update();
            }
        });
    }

    private void publish(Row row) {
        var record = new ProducerRecord<>(row.topic(), row.msgKey(), row.payload());
        record.headers()
                .add(Headers.K_EVENT_ID, row.id().toString().getBytes(StandardCharsets.UTF_8))
                .add(Headers.K_EVENT_TYPE, row.eventType().getBytes(StandardCharsets.UTF_8))
                .add(Headers.K_SESSION_ID, orEmpty(row.sessionId()).getBytes(StandardCharsets.UTF_8))
                .add(Headers.K_CORRELATION_ID, orEmpty(row.correlationId()).getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox row " + row.id(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed publishing outbox row " + row.id(), e.getCause());
        }
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }

    private record Row(UUID id, String topic, String msgKey, String eventType,
                        String payload, String sessionId, String correlationId) {}
}
