package com.quickbite.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OrderTest {
    private Order newOrder() {
        return Order.place(1L, "alice", "r1",
                List.of(new OrderLine("i1", "Dosa", 2, new BigDecimal("120.00")),
                        new OrderLine("i2", "Coffee", 1, new BigDecimal("40.50"))),
                12.9, 77.6, "key-1");
    }

    @Test
    void totalIsComputedFromServerPrices() {
        assertThat(newOrder().getTotalAmount()).isEqualByComparingTo("280.50");
    }

    @Test
    void emptyOrderRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(1L, "a", "r", List.of(), 0, 0, null));
    }

    @Test
    void happyPathTransitions() {
        Order o = newOrder();
        o.transitionTo(OrderStatus.PAID);
        o.assignRider("bob");
        o.transitionTo(OrderStatus.PICKED_UP);
        o.transitionTo(OrderStatus.DELIVERED);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @ParameterizedTest
    @CsvSource({ "PENDING_PAYMENT,DELIVERED", "PENDING_PAYMENT,RIDER_ASSIGNED", "PAID,PICKED_UP" })
    void illegalTransitionsRejected(OrderStatus from, OrderStatus to) {
        Order o = newOrder();
        if (from == OrderStatus.PAID) o.transitionTo(OrderStatus.PAID);
        assertThatIllegalStateException().isThrownBy(() -> o.transitionTo(to));
    }

    @Test
    void terminalStatesAreFinal() {
        Order o = newOrder();
        o.transitionTo(OrderStatus.CANCELLED);
        for (OrderStatus s : OrderStatus.values()) assertThat(OrderStatus.CANCELLED.canMoveTo(s)).isFalse();
    }

    @Test
    void onlyTheAssignedRiderMayAct() {
        Order o = newOrder();
        o.transitionTo(OrderStatus.PAID);
        o.assignRider("bob");
        assertThatThrownBy(() -> o.requireRider("carol")).isInstanceOf(AccessDeniedException.class);
        assertThatCode(() -> o.requireRider("bob")).doesNotThrowAnyException();
    }
}