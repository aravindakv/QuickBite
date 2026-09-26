package com.quickbite.payment.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class TopicConfig {
    @Bean
    KafkaAdmin.NewTopics paymentTopics(@Value("${KAFKA_PARTITIONS:3}") int p, @Value("${KAFKA_REPLICAS:1}") short r) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("orders.events").partitions(p).replicas(r).build(),
                TopicBuilder.name("orders.events.dlt").partitions(p).replicas(r).build(),
                TopicBuilder.name("payments.events").partitions(p).replicas(r).build());
    }
}