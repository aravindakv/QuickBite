package com.quickbite.location.messaging;

import com.quickbite.location.app.RiderLocationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.quickbite.common.kafka.KafkaErrorHandlingAutoConfiguration.header;

@Component
public class OrderEventsListener {
    record StatusChanged(String orderId, String customerId, String status, String riderId) {}
    private final RiderLocationService service;
    private final JsonMapper json;

    public OrderEventsListener(RiderLocationService service, JsonMapper json) { this.service = service; this.json = json; }

    @KafkaListener(topics = "orders.events")
    public void on(ConsumerRecord<String, String> rec) {
        if (!"order.status-changed".equals(header(rec, "eventType"))) return;
        StatusChanged e = json.readValue(rec.value(), StatusChanged.class);
        if (e.riderId() != null && ("DELIVERED".equals(e.status()) || "CANCELLED".equals(e.status()))) {
            service.release(e.riderId(), e.orderId());   // idempotent by nature: second release returns 0
        }
    }
}