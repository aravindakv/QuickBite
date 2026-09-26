package com.quickbite.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    public enum Status { AUTHORIZED, FAILED, CAPTURED }

    @Id private UUID id;
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    @Enumerated(EnumType.STRING) private Status status;
    private String pspReference;
    private String failureReason;
    private String paymentMethodId;
    private String cardBrand;
    private String cardLast4;
    private Instant createdAt;
    private Instant updatedAt;

    protected Payment() {}

    public static Payment authorized(String orderId, String customerId, BigDecimal amount, String currency,
                                     PaymentMethod pm, String pspRef) {
        return create(orderId, customerId, amount, currency, Status.AUTHORIZED, pm, pspRef, null);
    }

    /** pm may be null (e.g. NO_PAYMENT_METHOD). */
    public static Payment failed(String orderId, String customerId, BigDecimal amount, String currency,
                                 PaymentMethod pm, String reason) {
        return create(orderId, customerId, amount, currency, Status.FAILED, pm, null, reason);
    }

    private static Payment create(String orderId, String customerId, BigDecimal amount, String currency,
                                  Status status, PaymentMethod pm, String ref, String reason) {
        var p = new Payment();
        p.id = UUID.randomUUID(); p.orderId = orderId; p.customerId = customerId; p.amount = amount;
        p.currency = currency; p.status = status; p.pspReference = ref; p.failureReason = reason;
        if (pm != null) { p.paymentMethodId = pm.getId(); p.cardBrand = pm.getBrand(); p.cardLast4 = pm.getLast4(); }
        p.createdAt = p.updatedAt = Instant.now();
        return p;
    }

    public void capture() {
        if (status != Status.AUTHORIZED) throw new IllegalStateException("cannot capture a " + status + " payment");
        status = Status.CAPTURED; updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getPspReference() { return pspReference; }
    public String getFailureReason() { return failureReason; }
    public String getCardBrand() { return cardBrand; }
    public String getCardLast4() { return cardLast4; }
}