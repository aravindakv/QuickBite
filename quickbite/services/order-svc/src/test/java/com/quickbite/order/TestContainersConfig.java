package com.quickbite.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres + real Kafka, started once and shared. @ServiceConnection wires the URLs automatically. */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("orders"); }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() { return new KafkaContainer("apache/kafka-native:4.1.0"); }
}