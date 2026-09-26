package com.quickbite.order.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record PlaceOrderRequest(
        @NotBlank String restaurantId,
        @NotEmpty @Size(max = 50) List<@Valid Item> items,
        @DecimalMin("-90") @DecimalMax("90") double deliveryLat,
        @DecimalMin("-180") @DecimalMax("180") double deliveryLon,
        @Size(max = 40) String paymentMethodId) {      // optional: null -> the customer's default card (file 07)
    public record Item(@NotBlank String menuItemId, @Min(1) @Max(20) int quantity) {}
    // Note: NO price field. Clients never tell the server what things cost.
    // Note: NO card number either. Only an opaque token id ("pm_..."); card data never touches order-svc.
}