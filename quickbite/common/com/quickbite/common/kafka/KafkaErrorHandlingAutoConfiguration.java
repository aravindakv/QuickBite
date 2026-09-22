package com.quickbite.common.kafka;

import com.quickbite.common.context.Headers;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;

@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class KafkaErrorHandlingAutoConfiguration {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + ".dlt", rec.partition()));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        // Bad JSON will never succeed on retry -> straight to DLT
        handler.addNotRetryableExceptions(tools.jackson.core.JacksonException.class, IllegalArgumentException.class);
        return handler;
    }

    @Bean
    RecordInterceptor<Object, Object> mdcRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> r, Consumer<Object, Object> c) {
                MDC.put(Headers.MDC_SESSION, header(r, Headers.K_SESSION_ID));
                MDC.put(Headers.MDC_CORRELATION, header(r, Headers.K_CORRELATION_ID));
                return r;
            }
            @Override
            public void afterRecord(ConsumerRecord<Object, Object> r, Consumer<Object, Object> c) {
                MDC.remove(Headers.MDC_SESSION);
                MDC.remove(Headers.MDC_CORRELATION);
            }
        };
    }

    public static String header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? "-" : new String(h.value(), StandardCharsets.UTF_8);
    }
}