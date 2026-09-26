package com.quickbite.order.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Each service declares every topic it touches. KafkaAdmin creates missing ones at startup (idempotent). */
@Configuration
public class TopicConfig {
    @Bean
    KafkaAdmin.NewTopics orderTopics(@Value("${KAFKA_PARTITIONS:3}") int partitions, @Value("${KAFKA_REPLICAS:1}") short replicas) {
        return new KafkaAdmin.NewTopics(
                topic("orders.events", partitions, replicas),
                topic("payments.events", partitions, replicas),
                topic("payments.events.dlt", partitions, replicas));   // DLT for the topic WE consume
    }
    private static NewTopic topic(String name, int p, short r) { return TopicBuilder.name(name).partitions(p).replicas(r).build(); }
}