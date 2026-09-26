package com.quickbite.location.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class TopicConfig {
    @Bean
    KafkaAdmin.NewTopics locationTopics(@Value("${KAFKA_PARTITIONS:3}") int p, @Value("${KAFKA_REPLICAS:1}") short r) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("rider.location").partitions(p).replicas(r)
                        .config("retention.ms", String.valueOf(24 * 3600 * 1000L)).build(),   // 24 h: it's a firehose
                TopicBuilder.name("orders.events").partitions(p).replicas(r).build(),
                TopicBuilder.name("orders.events.dlt").partitions(p).replicas(r).build());
    }
}