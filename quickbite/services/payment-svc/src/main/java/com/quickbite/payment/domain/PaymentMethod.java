package com.quickbite.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_methods")
public class PaymentMethod {
    @Id private String id;
    private String customerId;
    private String pspToken;
    private String brand;
    private String last4;
    private int expMonth;
    private int expYear;
    private String holderName;
    private boolean isDefault;
    private Instant createdAt;

    protected PaymentMethod() {}

    public static PaymentMethod create(String customerId, String pspToken, String brand, String last4,
                                       int expMonth, int expYear, String holderName, boolean isDefault) {
        var pm = new PaymentMethod();
        pm.id = "pm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        pm.customerId = customerId; pm.pspToken = pspToken; pm.brand = brand; pm.last4 = last4;
        pm.expMonth = expMonth; pm.expYear = expYear; pm.holderName = holderName;
        pm.isDefault = isDefault; pm.createdAt = Instant.now();
        return pm;
    }

    public void markDefault() { isDefault = true; }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getPspToken() { return pspToken; }
    public String getBrand() { return brand; }
    public String getLast4() { return last4; }
    public int getExpMonth() { return expMonth; }
    public int getExpYear() { return expYear; }
    public String getHolderName() { return holderName; }
    public boolean isDefault() { return isDefault; }
}