package com.quickbite.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByClientRequestId(String clientRequestId);
    List<Order> findTop20ByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Order> findTop20ByStatusOrderByCreatedAtAsc(OrderStatus status);
    Optional<Order> findFirstByRiderIdAndStatusIn(String riderId, List<OrderStatus> statuses);
}