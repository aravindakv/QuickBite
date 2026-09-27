package com.quickbite.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quickbite.app.auth.AuthManager
import com.quickbite.app.net.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import java.util.UUID

enum class Screen { Login, Restaurants, Menu, Tracking, Rider }

data class UiState(
    val screen: Screen = Screen.Login,
    val user: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val restaurants: List<RestaurantSummary> = emptyList(),
    val detail: RestaurantDetail? = null,
    val cart: Map<String, Int> = emptyMap(),
    val order: OrderDto? = null,
    val riderPos: Pair<Double, Double>? = null,
    val log: List<String> = emptyList(),
    val riderOnline: Boolean = false,
    val riderOrder: OrderDto? = null,
    val city: String = "blr",
    val cards: List<PaymentMethod> = emptyList(),
    val selectedCardId: String? = null,        // null = default card
    val testCards: List<TestCard> = emptyList(),
    val showAddCard: Boolean = false,
    val payment: PaymentView? = null,          // shown on the tracking screen (e.g. decline reason)
    val riderPath: List<Pair<Double, Double>> = emptyList(),
    val etaMinutes: Int? = null,
    val distanceKm: Double? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val auth = AuthManager(app)
    private val http = Network.client(auth)
    private val api = Network.api(http)
    private val realtime = Realtime(http)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var updatesJob: Job? = null
    private var riderJob: Job? = null
    private var checkoutKey: String = UUID.randomUUID().toString()   // one Idempotency-Key per checkout attempt

    // Fixed demo delivery address. Exercise: replace with FusedLocationProviderClient.
    private val home = 12.9279 to 77.6271

    init { if (auth.isLoggedIn) afterLogin() }

    // ---------- auth ----------
    suspend fun loginIntent(): Intent = auth.loginIntent()

    fun onLoginResult(data: Intent?) = launchSafe {
        if (data == null) error("Login cancelled")
        auth.completeLogin(data)
        afterLogin()
    }

    private fun afterLogin() {
        val roles = auth.roles()
        _state.update { it.copy(user = auth.username(), screen = if ("rider" in roles) Screen.Rider else Screen.Restaurants) }
        startUpdates()
        if ("rider" !in roles) { loadRestaurants(); loadCards() }
    }

    suspend fun logoutIntent(): Intent = auth.endSessionIntent()

    /** Called with the end-session browser result. Local state is cleared regardless of that outcome. */
    fun onLogoutResult(data: Intent?) = launchSafe {
        auth.logEndSessionResult(data)
        runCatching { api.logout() }          // server-side: revoke the whole session (sid denylist)
        updatesJob?.cancel(); riderJob?.cancel()
        auth.clear()
        _state.value = UiState()
    }

    // ---------- customer ----------
    fun loadRestaurants(city: String = _state.value.city) = launchSafe {
        _state.update { it.copy(city = city, restaurants = api.restaurants(city)) }
    }

    // ---------- cards ----------
    fun loadCards() = launchSafe {
        val cards = api.cards()
        val tests = if (com.quickbite.app.BuildConfig.DEBUG) runCatching { api.testCards() }.getOrDefault(emptyList()) else emptyList()
        _state.update { it.copy(cards = cards, testCards = tests,
            selectedCardId = it.selectedCardId ?: cards.firstOrNull { c -> c.isDefault }?.id) }
    }

    fun selectCard(id: String) = _state.update { it.copy(selectedCardId = id) }
    fun showAddCard(show: Boolean) = _state.update { it.copy(showAddCard = show, error = null) }

    fun addCard(number: String, expMonth: Int, expYear: Int, cvc: String) = launchSafe {
        val card = api.addCard(AddCardRequest(number, expMonth, expYear, cvc, _state.value.user))
        _state.update { it.copy(cards = it.cards + card, selectedCardId = card.id, showAddCard = false) }
    }

    fun openRestaurant(id: String) = launchSafe {
        _state.update { it.copy(detail = api.restaurant(id), cart = emptyMap(), screen = Screen.Menu) }
        checkoutKey = UUID.randomUUID().toString()
    }

    fun changeQty(itemId: String, delta: Int) = _state.update {
        val q = ((it.cart[itemId] ?: 0) + delta).coerceIn(0, 20)
        it.copy(cart = if (q == 0) it.cart - itemId else it.cart + (itemId to q))
    }

    fun placeOrder() = launchSafe {
        val s = _state.value
        val req = PlaceOrderRequest(s.detail!!.restaurant.id,
            s.cart.map { PlaceOrderRequest.Item(it.key, it.value) }, home.first, home.second, s.selectedCardId)
        // If this call times out and the user taps again, the SAME key returns the SAME order.
        val order = api.placeOrder(checkoutKey, req)
        _state.update { it.copy(order = order, payment = null, riderPos = null,
            log = listOf("Order ${order.id}: ${order.status}"), screen = Screen.Tracking) }
        loadTrack(order.id)                     // empty at first; refreshed on every status change below
    }

    fun back() = _state.update { it.copy(screen = Screen.Restaurants, detail = null) }

    // ---------- realtime (both roles) ----------
    private fun startUpdates() {
        updatesJob?.cancel()
        updatesJob = viewModelScope.launch {
            realtime.updates().collect { raw ->
                val msg = Network.json.parseToJsonElement(raw).jsonObject
                when (msg["type"]?.jsonPrimitive?.content) {
                    "ORDER_STATUS" -> {
                        val status = msg["status"]!!.jsonPrimitive.content
                        val orderId = msg["orderId"]!!.jsonPrimitive.content
                        _state.update { st ->
                            st.copy(order = st.order?.takeIf { it.id == orderId }?.copy(status = status) ?: st.order,
                                log = (st.log + "Order $orderId → $status").takeLast(30))
                        }
                        if (_state.value.order?.id == orderId) {
                            loadTrack(orderId)                    // <-- backfill the path for the new status
                            if (status in setOf("PAID", "CANCELLED", "DELIVERED")) {
                                runCatching { api.payment(orderId) }.getOrNull()?.let { p -> _state.update { it.copy(payment = p) } }
                            }
                        }
                    }
                    "RIDER_LOCATION" -> {
                        val p = msg["lat"]!!.jsonPrimitive.double to msg["lon"]!!.jsonPrimitive.double
                        _state.update {
                            it.copy(riderPos = p,
                                riderPath = (it.riderPath + p).takeLast(300))   // bound the polyline
                        }
                    }
                }
            }
        }
    }

    // ---------- rider ----------
    fun toggleOnline() {
        if (_state.value.riderOnline) { riderJob?.cancel(); _state.update { it.copy(riderOnline = false) }; return }
        _state.update { it.copy(riderOnline = true, riderPos = 12.9345 to 77.6230) }
        riderJob = viewModelScope.launch {
            var socket: WebSocket = realtime.openRiderSocket()
            var tick = 0
            while (isActive) {
                val s = _state.value
                val pos = nextPosition(s)
                _state.update { it.copy(riderPos = pos) }
                if (!socket.send("""{"lat":${pos.first},"lon":${pos.second}}""")) socket = realtime.openRiderSocket()
                if (tick++ % 2 == 0) runCatching { api.riderActive() }.getOrNull()?.let { resp ->
                    _state.update { it.copy(riderOrder = if (resp.code() == 200) resp.body() else null) }
                }
                delay(2_000)
            }
            socket.close(1000, "offline")
        }
    }

    /** Simulated movement: after pickup, move 1/20 of the way to the customer each tick. */
    private fun nextPosition(s: UiState): Pair<Double, Double> {
        val cur = s.riderPos ?: (12.9345 to 77.6230)
        val o = s.riderOrder ?: return cur
        if (o.status != "PICKED_UP") return cur
        return (cur.first + (o.deliveryLat - cur.first) / 10) to (cur.second + (o.deliveryLon - cur.second) / 10)
    }

    fun pickup() = launchSafe { _state.value.riderOrder?.let { o -> _state.update { it.copy(riderOrder = api.pickup(o.id)) } } }
    fun deliver() = launchSafe { _state.value.riderOrder?.let { o -> api.deliver(o.id); _state.update { it.copy(riderOrder = null) } } }

    // ---------- helpers ----------
    /** Server errors are RFC 9457 problem+json: show the "detail" (e.g. "Invalid card number", "Dosa is sold out"). */
    private fun readableError(e: Exception): String =
        (e as? retrofit2.HttpException)?.response()?.errorBody()?.string()
            ?.let { runCatching { Network.json.parseToJsonElement(it).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull() }
            ?: e.message ?: e.toString()

    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        _state.update { it.copy(busy = true, error = null) }
        try { block() } catch (e: Exception) { _state.update { it.copy(error = readableError(e)) } }
        finally { _state.update { it.copy(busy = false) } }
    }

    /** Backfill the path drawn so far: called when the tracking screen opens and on every status change. */
    fun loadTrack(orderId: String) = viewModelScope.launch {
        val t = runCatching { api.track(orderId) }.getOrNull() ?: return@launch
        _state.update { st ->
            if (st.order?.id != orderId) st else st.copy(
                riderPath = t.path.map { it.lat to it.lon },
                riderPos = t.rider?.let { it.lat to it.lon } ?: st.riderPos,
                etaMinutes = t.etaMinutes, distanceKm = t.distanceKm)
        }
    }
}