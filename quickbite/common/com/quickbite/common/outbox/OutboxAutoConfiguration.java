package com.quickbite.common.outbox;

import com.quickbite.common.health.LoopWatchdog;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@EnableScheduling
@ConditionalOnProperty(name = "quickbite.outbox.enabled", havingValue = "true")
public class OutboxAutoConfiguration {
    @Bean OutboxWriter outboxWriter(JdbcClient jdbc, JsonMapper json) { return new OutboxWriter(jdbc, json); }
    @Bean IdempotencyGuard idempotencyGuard(JdbcClient jdbc) { return new IdempotencyGuard(jdbc); }
    @Bean OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
                                  TransactionTemplate tx, LoopWatchdog watchdog) {
        return new OutboxRelay(jdbc, kafka, tx, watchdog);
    }
}