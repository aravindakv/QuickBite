package com.quickbite.payment.messaging;

import com.quickbite.common.context.Headers;
import com.quickbite.payment.app.PaymentService;
import com.quickbite.payment.domain.Events;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration.header;

@Component
public class OrderEventsListener {
    private final PaymentService payments;
    private final JsonMapper json;

    public OrderEventsListener(PaymentService payments, JsonMapper json) { this.payments = payments; this.json = json; }

    @KafkaListener(topics = Events.ORDERS_TOPIC)
    public void on(ConsumerRecord<String, String> rec) {
        String eventId = header(rec, Headers.K_EVENT_ID);
        switch (header(rec, Headers.K_EVENT_TYPE)) {
            case "order.created" -> payments.onOrderCreated(eventId, json.readValue(rec.value(), Events.OrderCreated.class));
            case "order.status-changed" -> payments.onOrderStatusChanged(eventId, json.readValue(rec.value(), Events.OrderStatusChanged.class));
            default -> { /* ignore unknown types: forward compatibility */ }
        }
    }
}