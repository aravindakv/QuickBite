package com.quickbite.order.domain;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

@Embeddable
public record OrderLine(String menuItemId, String name, int quantity, BigDecimal unitPrice) {
    public BigDecimal lineTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}