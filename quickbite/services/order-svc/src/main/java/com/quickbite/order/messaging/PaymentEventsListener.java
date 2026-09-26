package com.quickbite.order.messaging;

import com.quickbite.common.context.Headers;
import com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration;
import com.quickbite.order.app.OrderService;
import com.quickbite.order.domain.Events;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentEventsListener {
    private final OrderService orders;
    private final JsonMapper json;

    public PaymentEventsListener(OrderService orders, JsonMapper json) { this.orders = orders; this.json = json; }

    @KafkaListener(topics = Events.PAYMENTS_TOPIC)
    public void on(ConsumerRecord<String, String> rec) {
        String eventId = KafkaErrorHandlingAutoConfiguration.header(rec, Headers.K_EVENT_ID);
        String type = KafkaErrorHandlingAutoConfiguration.header(rec, Headers.K_EVENT_TYPE);
        orders.onPaymentEvent(eventId, type, json.readValue(rec.value(), Events.PaymentEvent.class));
    }
}