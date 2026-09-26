package com.quickbite.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {
    List<PaymentMethod> findByCustomerIdOrderByCreatedAtAsc(String customerId);
    Optional<PaymentMethod> findByIdAndCustomerId(String id, String customerId);   // ownership built into the query
    Optional<PaymentMethod> findFirstByCustomerIdAndIsDefaultTrue(String customerId);
    long countByCustomerId(String customerId);

    /** Bulk update runs immediately, BEFORE we set the new default -> the partial unique index is never violated. */
    @Modifying
    @Query("update PaymentMethod p set p.isDefault = false where p.customerId = :customerId")
    void clearDefault(@Param("customerId") String customerId);
}
