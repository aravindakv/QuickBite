package com.quickbite.app.net

import kotlinx.serialization.Serializable

@Serializable data class RestaurantSummary(
    val id: String, val name: String, val cuisine: String, val area: String? = null, val rating: Double,
    val avgPrepMinutes: Int, val imageUrl: String? = null, val lat: Double, val lon: Double)

@Serializable data class MenuItem(
    val id: String, val name: String, val description: String? = null,
    val price: Double, val veg: Boolean, val available: Boolean, val imageUrl: String? = null)
// Double is fine for DISPLAY. The server computes every money value with BigDecimal.

@Serializable data class RestaurantDetail(val restaurant: RestaurantSummary, val menu: List<MenuItem>)

@Serializable data class PlaceOrderRequest(
    val restaurantId: String, val items: List<Item>, val deliveryLat: Double, val deliveryLon: Double,
    val paymentMethodId: String? = null) {          // null -> server uses the default card
    @Serializable data class Item(val menuItemId: String, val quantity: Int)
}

@Serializable data class OrderLine(val menuItemId: String, val name: String, val quantity: Int, val unitPrice: Double)

@Serializable data class OrderDto(
    val id: String,                       // Snowflake id as STRING (see file 02)
    val restaurantId: String, val status: String, val total: Double, val currency: String,
    val riderId: String? = null, val deliveryLat: Double, val deliveryLon: Double,
    val lines: List<OrderLine> = emptyList())

// ---- payments (the app only ever handles tokens + display data after the add-card call) ----
@Serializable data class PaymentMethod(
    val id: String, val brand: String, val last4: String, val expMonth: Int, val expYear: Int,
    val holderName: String? = null, val isDefault: Boolean)

@Serializable data class AddCardRequest(
    val number: String, val expMonth: Int, val expYear: Int, val cvc: String, val holderName: String? = null) {
    override fun toString() = "AddCardRequest(****, $expMonth/$expYear)"   // never let card data reach logcat
}

@Serializable data class PaymentView(
    val orderId: String, val status: String, val amount: Double, val currency: String,
    val cardBrand: String? = null, val cardLast4: String? = null, val failureReason: String? = null)

@Serializable data class TestCard(val number: String, val brand: String, val behavior: String,
                                  val declineCode: String? = null, val description: String)

@Serializable data class TrackPoint(val lat: Double, val lon: Double, val ts: Long = 0)

@Serializable data class TrackDto(
    val orderId: String, val status: String, val riderId: String? = null,
    val path: List<TrackPoint> = emptyList(), val rider: TrackPoint? = null,
    val destination: TrackPoint, val distanceKm: Double? = null, val etaMinutes: Int? = null)