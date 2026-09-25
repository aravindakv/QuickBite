package com.quickbite.catalog.domain;

import java.math.BigDecimal;

public record MenuItem(String id, String name, String description, BigDecimal price, boolean veg,
                       boolean available, String imageUrl) {
}
