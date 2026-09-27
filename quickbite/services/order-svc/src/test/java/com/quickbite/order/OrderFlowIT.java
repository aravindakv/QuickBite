package com.quickbite.order;

import com.quickbite.common.context.RequestContext;
import com.quickbite.order.app.OrderService;
import com.quickbite.order.app.PlaceOrderRequest;
import com.quickbite.order.client.Clients.*;
import com.quickbite.order.domain.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"quickbite.dispatch.interval-ms=3600000"})   // keep the dispatcher quiet
@Import(TestContainersConfig.class)
class OrderFlowIT {
    @MockitoBean CatalogClient catalog;     // other services are mocked at the HTTP-client boundary
    @MockitoBean LocationClient location;

    @Autowired OrderService orders;
    @Autowired OrderRepository repo;
    @Autowired JdbcClient jdbc;
    @Autowired KafkaContainer kafka;

    private PlaceOrderRequest request() {
        return new PlaceOrderRequest("r1", List.of(new PlaceOrderRequest.Item("i1", 2)), 12.9, 77.6, null); // null = default card
    }

    @Test
    void placingAnOrderPersistsItAndPublishesOrderCreatedViaOutbox() {
        when(catalog.prices(eq("r1"), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", new BigDecimal("150"), true)));

        Order o = orders.place("alice", request(), UUID.randomUUID().toString(), RequestContext.NONE);

        assertThat(o.getTotalAmount()).isEqualByComparingTo("300");
        // the relay publishes the outbox row to REAL Kafka
        try (var consumer = consumer()) {
            consumer.subscribe(List.of("orders.events"));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var found = new ArrayList<String>();
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.key().equals(o.getId().toString()))
                        found.add(new String(r.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8));
                });
                assertThat(found).contains("order.created");
            });
        }
        Integer pending = jdbc.sql("select count(*) from outbox where published_at is null").query(Integer.class).single();
        assertThat(pending).isZero();
    }

    @Test
    void sameIdempotencyKeyReturnsSameOrder() {
        when(catalog.prices(any(), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", BigDecimal.TEN, true)));
        String key = UUID.randomUUID().toString();
        Order first = orders.place("alice", request(), key, RequestContext.NONE);
        Order second = orders.place("alice", request(), key, RequestContext.NONE);
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void duplicatePaymentEventIsAppliedOnce() {
        when(catalog.prices(any(), anyList())).thenReturn(List.of(new MenuItemPrice("i1", "Dosa", BigDecimal.TEN, true)));
        Order o = orders.place("alice", request(), null, RequestContext.NONE);
        var evt = new Events.PaymentEvent(o.getId().toString(), "p1", null);

        orders.onPaymentEvent("evt-123", "payment.authorized", evt, RequestContext.NONE);
        orders.onPaymentEvent("evt-123", "payment.authorized", evt, RequestContext.NONE);    // redelivery: must be a no-op, not an error

        assertThat(repo.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        Integer statusEvents = jdbc.sql("select count(*) from outbox where msg_key = ? and event_type = 'order.status-changed'")
                .param(o.getId().toString()).query(Integer.class).single();
        assertThat(statusEvents).isEqualTo(1);
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
    }
}