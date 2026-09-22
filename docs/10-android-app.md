# 10 — Android App: AppAuth (PKCE), Retrofit, WebSockets, Live Map

**Goal:** one Kotlin/Compose app with two modes, chosen from the roles in the JWT:

- **Customer (alice):** log in, browse, order, and watch the order and the rider move live on a map.
- **Rider (bob):** go online, stream GPS over a WebSocket, pick up and deliver.

You'll run it against the stack from file 09 on an emulator or a USB-connected phone.

---

## Concepts first

### OAuth2 on mobile: authorization code + PKCE via the system browser

```
App ──(1) opens Custom Tab──► Keycloak login page (http://localhost:8180, via adb reverse)
    ◄─(2) redirect com.quickbite.app:/oauth2redirect?code=XYZ
App ──(3) POST /token {code, code_verifier}──► Keycloak ──► access + refresh + id tokens
```

- **Why the system browser, not a WebView?** The app never sees the password, and a WebView would let a malicious app keylog it. The browser also shares SSO cookies. This is RFC 8252 ("OAuth for native apps").
- **PKCE:** the app makes a random `code_verifier` and sends only its SHA-256 hash (`code_challenge`) in step 1. In step 3 it proves possession of the verifier. An intercepted `code` is useless without it. AppAuth does all of this automatically.
- **Token storage:** tokens are encrypted with an AES key that lives in the **Android Keystore** (hardware-backed on most devices). The key never leaves secure hardware.

### Networking on the device

- REST goes through OkHttp → `localhost:8000` → `adb reverse` → NGINX → gateway.
- An OkHttp **interceptor** adds a fresh bearer token to every request, refreshing it via AppAuth when it's within 60 s of expiry.
- WebSockets use the same OkHttp client (and the same interceptor). They reconnect with **exponential backoff + jitter** (1 s → 2 s → 4 s … max 30 s, ± random). Without jitter, when the server restarts, 100k phones reconnect in the same second; that is the thundering herd again.

---

## Step 1: Create the project

In Android Studio: **New Project → Empty Activity (Compose)**:

- Name: `QuickBite`
- Package: `com.quickbite.app`
- Location: `quickbite/clients/android`
- Min SDK: 26
- Build language: Kotlin DSL

Keep the AGP/Kotlin versions the wizard generates.

---

## Step 2: Gradle

In `clients/android/app/build.gradle.kts`, add or merge the following.

```kotlin
plugins {
    // keep the wizard's android.application / kotlin.android / kotlin.compose plugins, and add:
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0" // match your Kotlin version
}

android {
    namespace = "com.quickbite.app"
    defaultConfig {
        applicationId = "com.quickbite.app"
        minSdk = 26
        // AppAuth's redirect receiver activity is registered for this scheme:
        manifestPlaceholders["appAuthRedirectScheme"] = "com.quickbite.app"

        buildConfigField("String", "API_BASE_URL", "\"http://localhost:8000\"")
        buildConfigField("String", "WS_BASE_URL", "\"ws://localhost:8000\"")
        buildConfigField("String", "ISSUER", "\"http://localhost:8180/realms/quickbite\"")
        buildConfigField("String", "CLIENT_ID", "\"android-app\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // (keep the wizard's Compose BOM / material3 / activity-compose dependencies)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.browser:browser:1.8.0")                  // Custom Tabs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.osmdroid:osmdroid-android:6.1.20")            // OpenStreetMap: no API key needed

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
```

> If any version fails to resolve, use the newest stable release; none of the APIs used below are new.

---

## Step 3: Manifest and network security

`app/src/main/AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".QuickBiteApp"
        android:label="QuickBite"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@style/Theme.QuickBite">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <!-- AppAuth's RedirectUriReceiverActivity is merged in automatically via appAuthRedirectScheme -->
    </application>
</manifest>
```

`app/src/main/res/xml/network_security_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Cleartext ONLY for local development hosts. Production must be HTTPS-only (and ideally pinned). -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

**Production hardening** (do it once the app works):

- Remove this cleartext block and use HTTPS.
- Add `<pin-set>` certificate pinning for your API domain.
- Add Play Integrity attestation.

---

## Step 4: Code

All files live under `app/src/main/java/com/quickbite/app/`.

`QuickBiteApp.kt`

```kotlin
package com.quickbite.app

import android.app.Application
import org.osmdroid.config.Configuration

class QuickBiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName // OSM tile servers require a user agent
    }
}
```

### 4.1 Secure token storage (Android Keystore)

`auth/SecureStore.kt`

```kotlin
package com.quickbite.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable Keystore key. Values are stored as base64(iv || ciphertext). */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)
    private val alias = "quickbite_token_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = try {
        prefs.getString(name, null)?.let {
            val bytes = Base64.decode(it, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }
    } catch (e: Exception) { remove(name); null } // key invalidated (e.g. lock screen reset) -> force re-login

    fun remove(name: String) = prefs.edit().remove(name).apply()
}
```

### 4.2 AppAuth wrapper

`auth/AuthManager.kt`

```kotlin
package com.quickbite.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.quickbite.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import net.openid.appauth.*
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** AppAuth refuses http:// by default (correctly!). For LOCAL DEBUG ONLY we allow it. */
private object DevConnectionBuilder : ConnectionBuilder {
    override fun openConnection(uri: Uri): HttpURLConnection =
        (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 10_000; instanceFollowRedirects = false
        }
}

class AuthManager(context: Context) {
    private val store = SecureStore(context)
    private val connectionBuilder: ConnectionBuilder =
        if (BuildConfig.DEBUG) DevConnectionBuilder else DefaultConnectionBuilder.INSTANCE
    private val service = AuthorizationService(
        context, AppAuthConfiguration.Builder().setConnectionBuilder(connectionBuilder).build()
    )

    @Volatile var state: AuthState = store.get(KEY)?.let { AuthState.jsonDeserialize(it) } ?: AuthState()
        private set

    val isLoggedIn: Boolean get() = state.isAuthorized

    suspend fun loginIntent(): Intent {
        val config = discover()
        val request = AuthorizationRequest.Builder(
            config, BuildConfig.CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI)
        ).setScopes("openid", "profile").build()   // PKCE code_verifier/challenge generated here automatically
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun completeLogin(data: Intent) {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        state.update(response, error)
        if (response == null) throw error ?: IllegalStateException("Login cancelled")
        val tokens = suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { r, e ->
                if (r != null) cont.resume(r) else cont.resumeWithException(e ?: IllegalStateException("token exchange failed"))
            }
        }
        state.update(tokens, null)
        persist()
    }

    /** Returns a valid access token, refreshing (with rotation) if it's about to expire. */
    suspend fun freshAccessToken(): String = suspendCancellableCoroutine { cont ->
        state.performActionWithFreshTokens(service) { access, _, ex ->
            persist()                                  // refresh rotated the refresh token: save the new one
            if (access != null) cont.resume(access)
            else cont.resumeWithException(ex ?: IllegalStateException("Not logged in"))
        }
    }

    /** Realm roles from the access token payload (display only: the SERVER enforces roles). */
    fun roles(): Set<String> {
        val jwt = state.accessToken ?: return emptySet()
        val payload = String(Base64.decode(jwt.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        return Json.parseToJsonElement(payload).jsonObject["realm_access"]?.jsonObject?.get("roles")
            ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
    }

    fun username(): String = state.accessToken?.let { jwt ->
        val payload = String(Base64.decode(jwt.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        Json.parseToJsonElement(payload).jsonObject["preferred_username"]?.jsonPrimitive?.content
    } ?: "?"

    fun clear() { state = AuthState(); store.remove(KEY) }

    private fun persist() = store.put(KEY, state.jsonSerializeString())

    private suspend fun discover(): AuthorizationServiceConfiguration =
        state.authorizationServiceConfiguration ?: suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.ISSUER), { cfg, ex ->
                if (cfg != null) { state = AuthState(cfg); cont.resume(cfg) }
                else cont.resumeWithException(ex ?: IllegalStateException("OIDC discovery failed"))
            }, connectionBuilder)
        }

    companion object {
        private const val KEY = "auth_state"
        const val REDIRECT_URI = "com.quickbite.app:/oauth2redirect"
    }
}
```

### 4.3 API models and Retrofit

`net/Models.kt`

```kotlin
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
```

`net/Api.kt`

```kotlin
package com.quickbite.app.net

import com.quickbite.app.BuildConfig
import com.quickbite.app.auth.AuthManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import java.util.UUID
import java.util.concurrent.TimeUnit

interface QuickBiteApi {
    @GET("api/restaurants") suspend fun restaurants(@Query("city") city: String = "blr"): List<RestaurantSummary>
    @GET("api/restaurants/{id}") suspend fun restaurant(@Path("id") id: String): RestaurantDetail
    @POST("api/orders") suspend fun placeOrder(@Header("Idempotency-Key") key: String, @Body body: PlaceOrderRequest): OrderDto
    @GET("api/orders/{id}") suspend fun order(@Path("id") id: String): OrderDto
    @GET("api/orders/rider/active") suspend fun riderActive(): Response<OrderDto>   // 204 when nothing assigned
    @POST("api/orders/{id}/pickup") suspend fun pickup(@Path("id") id: String): OrderDto
    @POST("api/orders/{id}/deliver") suspend fun deliver(@Path("id") id: String): OrderDto
    @POST("api/auth/logout") suspend fun logout(): Response<Unit>

    @GET("api/payments/methods") suspend fun cards(): List<PaymentMethod>
    @POST("api/payments/methods") suspend fun addCard(@Body body: AddCardRequest): PaymentMethod
    @POST("api/payments/methods/{id}/default") suspend fun makeDefault(@Path("id") id: String): Response<Unit>
    @GET("api/payments/orders/{orderId}") suspend fun payment(@Path("orderId") orderId: String): PaymentView
    @GET("api/payments/test-cards") suspend fun testCards(): List<TestCard>          // debug builds only
}

object Network {
    val json = Json { ignoreUnknownKeys = true }   // tolerant reader: server may add fields

    fun client(auth: AuthManager): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val b = chain.request().newBuilder().header("X-Correlation-Id", UUID.randomUUID().toString())
            if (auth.isLoggedIn) {
                // OkHttp runs interceptors on its own background threads, so blocking here is acceptable.
                runCatching { runBlocking { auth.freshAccessToken() } }.getOrNull()
                    ?.let { b.header("Authorization", "Bearer $it") }
            }
            chain.proceed(b.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) // BASIC = no bodies -> card numbers never logged
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)        // WebSocket keep-alive at the protocol level
        .build()

    fun api(client: OkHttpClient): QuickBiteApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(QuickBiteApi::class.java)
}
```

### 4.4 WebSockets with reconnect

`net/Realtime.kt`

```kotlin
package com.quickbite.app.net

import com.quickbite.app.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import okhttp3.*
import kotlin.math.min
import kotlin.random.Random

class Realtime(private val client: OkHttpClient) {

    /** Infinite stream of server messages; reconnects with exponential backoff + jitter. */
    fun updates(path: String = "/ws/updates"): Flow<String> = flow {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                session(path).collect { backoff = 1_000L; emit(it) }   // healthy traffic resets the backoff
            } catch (e: Exception) { /* fall through to reconnect */ }
            delay(backoff + Random.nextLong(0, 500))
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private fun session(path: String): Flow<String> = callbackFlow {
        val ws = client.newWebSocket(Request.Builder().url(BuildConfig.WS_BASE_URL + path).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { trySend(text) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            })
        awaitClose { ws.close(1000, "bye") }
    }

    /** Rider GPS channel (send-only). Caller re-opens it if send() returns false. */
    fun openRiderSocket(): WebSocket =
        client.newWebSocket(Request.Builder().url(BuildConfig.WS_BASE_URL + "/ws/rider").build(), object : WebSocketListener() {})
}
```

### 4.5 ViewModel (all app logic)

`ui/AppViewModel.kt`

```kotlin
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

    fun logout() = launchSafe {
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
                        // Payment outcome (card used, decline reason) once it's decided
                        if (status in setOf("PAID", "CANCELLED", "DELIVERED") && _state.value.order?.id == orderId) {
                            runCatching { api.payment(orderId) }.getOrNull()?.let { p -> _state.update { it.copy(payment = p) } }
                        }
                    }
                    "RIDER_LOCATION" -> _state.update {
                        it.copy(riderPos = msg["lat"]!!.jsonPrimitive.double to msg["lon"]!!.jsonPrimitive.double)
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
        try { block() } catch (e: Exception) { _state.update { it.copy(error = readableError(e)) } }
        finally { _state.update { it.copy(busy = false) } }
    }
}
```

### 4.6 Map composable

`ui/MapPane.kt`

```kotlin
package com.quickbite.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapPane(destination: Pair<Double, Double>, rider: Pair<Double, Double>?) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(280.dp),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            val dest = GeoPoint(destination.first, destination.second)
            map.overlays.add(Marker(map).apply { position = dest; title = "You" })
            rider?.let { map.overlays.add(Marker(map).apply { position = GeoPoint(it.first, it.second); title = "Rider" }) }
            map.controller.setCenter(rider?.let { GeoPoint(it.first, it.second) } ?: dest)
            map.invalidate()
        })
}
```

### 4.7 Screens

`ui/Screens.kt`

```kotlin
package com.quickbite.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(s: UiState, vm: AppViewModel, onLogin: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (s.user.isBlank()) "QuickBite" else "QuickBite · ${s.user}") },
            actions = { if (s.screen != Screen.Login) TextButton(onClick = vm::logout) { Text("Logout") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (s.screen) {
                Screen.Login -> Login(onLogin)
                Screen.Restaurants -> Restaurants(s, vm)
                Screen.Menu -> Menu(s, vm)
                Screen.Tracking -> Tracking(s, vm)
                Screen.Rider -> Rider(s, vm)
            }
        }
    }
}

@Composable private fun Login(onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Log in as alice/alice (customer) or bob/bob (rider)")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLogin) { Text("Log in with QuickBite ID") }
    }
}

@Composable private fun Restaurants(s: UiState, vm: AppViewModel) {
    Row {
        listOf("blr" to "Bengaluru", "mum" to "Mumbai").forEach { (code, label) ->
            FilterChip(selected = s.city == code, onClick = { vm.loadRestaurants(code) }, label = { Text(label) })
            Spacer(Modifier.width(8.dp))
        }
    }
    LazyColumn {
        items(s.restaurants, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.openRestaurant(r.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text("${r.cuisine} · ${r.area ?: ""} · ★ ${r.rating} · ${r.avgPrepMinutes} min")
                }
            }
        }
    }
}

@Composable private fun Menu(s: UiState, vm: AppViewModel) {
    val d = s.detail ?: return
    TextButton(onClick = vm::back) { Text("← Restaurants") }
    Text(d.restaurant.name, style = MaterialTheme.typography.headlineSmall)
    LazyColumn(Modifier.weight(1f)) {
        items(d.menu, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name + if (m.veg) " 🟢" else " 🔴")
                    Text(if (m.available) "₹%.0f".format(m.price) else "Sold out", style = MaterialTheme.typography.bodySmall)
                }
                // Sold-out items stay tappable ON PURPOSE in debug builds: it lets you test the server's 409.
                OutlinedButton(onClick = { vm.changeQty(m.id, -1) }) { Text("−") }
                Text("${s.cart[m.id] ?: 0}", Modifier.padding(horizontal = 8.dp))
                OutlinedButton(onClick = { vm.changeQty(m.id, +1) }) { Text("+") }
            }
        }
    }
    CardPicker(s, vm)
    val total = d.menu.sumOf { (s.cart[it.id] ?: 0) * it.price }
    Button(onClick = vm::placeOrder, enabled = s.cart.isNotEmpty() && !s.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Place order · ₹%.0f (final price set by server)".format(total))
    }
}

@Composable private fun Tracking(s: UiState, vm: AppViewModel) {
    val o = s.order ?: return
    Text("Order ${o.id}", style = MaterialTheme.typography.titleMedium)
    Text("Status: ${o.status}", style = MaterialTheme.typography.headlineSmall)
    s.payment?.let { p ->
        Text("Payment: ${p.status} · ${p.cardBrand ?: ""} ••${p.cardLast4 ?: ""}")
        p.failureReason?.let { Text("Reason: $it", color = MaterialTheme.colorScheme.error) }
    }
    Spacer(Modifier.height(8.dp))
    MapPane(destination = o.deliveryLat to o.deliveryLon, rider = s.riderPos)
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.weight(1f)) { items(s.log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    if (o.status == "DELIVERED" || o.status == "CANCELLED") Button(onClick = vm::back) { Text("Order again") }
}

@Composable private fun Rider(s: UiState, vm: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.riderOnline) "You are ONLINE" else "You are offline", Modifier.weight(1f))
        Switch(checked = s.riderOnline, onCheckedChange = { vm.toggleOnline() })
    }
    val o = s.riderOrder
    if (o == null) { Text("Waiting for an order…"); return }
    Text("Order ${o.id} · ${o.status}", style = MaterialTheme.typography.titleMedium)
    MapPane(destination = o.deliveryLat to o.deliveryLon, rider = s.riderPos)
    Row {
        Button(onClick = vm::pickup, enabled = o.status == "RIDER_ASSIGNED") { Text("Picked up") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = vm::deliver, enabled = o.status == "PICKED_UP") { Text("Delivered") }
    }
}
```

`ui/CardUi.kt`

```kotlin
package com.quickbite.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CardPicker(s: UiState, vm: AppViewModel) {
    Text("Pay with", style = MaterialTheme.typography.titleSmall)
    LazyRow {
        items(s.cards, key = { it.id }) { c ->
            FilterChip(
                selected = s.selectedCardId == c.id,
                onClick = { vm.selectCard(c.id) },
                label = { Text("${c.brand} ••${c.last4}" + if (c.isDefault) " ★" else "") })
            Spacer(Modifier.width(6.dp))
        }
        item { AssistChip(onClick = { vm.showAddCard(true) }, label = { Text("+ Add card") }) }
    }
    if (s.showAddCard) AddCardDialog(s, vm)
}

@Composable
private fun AddCardDialog(s: UiState, vm: AppViewModel) {
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("12/30") }
    var cvc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { vm.showAddCard(false) },
        title = { Text("Add card") },
        text = {
            Column {
                OutlinedTextField(number, { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23) },
                    label = { Text("Card number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row {
                    OutlinedTextField(expiry, { expiry = it.take(5) }, label = { Text("MM/YY") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(cvc, { cvc = it.filter(Char::isDigit).take(4) }, label = { Text("CVC") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f))
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }   // e.g. "Invalid card number"
                if (s.testCards.isNotEmpty()) {                                   // DEBUG builds only
                    Spacer(Modifier.height(8.dp))
                    Text("Test cards (tap to fill)", style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.heightIn(max = 180.dp)) {
                        s.testCards.forEach { t ->
                            TextButton(onClick = {
                                number = t.number.chunked(4).joinToString(" ")
                                cvc = if (t.brand == "AMEX") "1234" else "123"
                            }) { Text("••${t.number.takeLast(4)} ${t.brand}: ${t.description}") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !s.busy, onClick = {
                val (mm, yy) = expiry.split("/").map { it.trim().toIntOrNull() ?: 0 }.let { it[0] to (it.getOrElse(1) { 0 }) }
                vm.addCard(number.replace(" ", ""), mm, 2000 + yy, cvc)          // validated again by the PSP (Luhn, expiry, CVC)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { vm.showAddCard(false) }) { Text("Cancel") } })
}
```

> **Security notes for the card form:** the CVC field is masked; `AddCardRequest.toString()` hides the number; HTTP logging is `BASIC` (no bodies). In production you'd use the PSP's Android SDK card form, which sends card data straight to the provider, and add `FLAG_SECURE` to the window to block screenshots.

`MainActivity.kt`

```kotlin
package com.quickbite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.quickbite.app.ui.AppScaffold
import com.quickbite.app.ui.AppViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onLoginResult(it.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                AppScaffold(state, vm) { lifecycleScope.launch { loginLauncher.launch(vm.loginIntent()) } }
            }
        }
    }
}
```

---

## Step 5: Connect the device to your stack

`scripts/android-reverse.sh`

```bash
#!/usr/bin/env bash
# Re-run whenever a device/emulator (re)connects.
for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  adb -s "$d" reverse tcp:8000 tcp:8000     # API + WebSockets (NGINX)
  adb -s "$d" reverse tcp:8180 tcp:8180     # Keycloak login page + token endpoint
  echo "✔ $d: $(adb -s "$d" reverse --list | wc -l | tr -d ' ') reverse rules"
done
```

```bash
chmod +x scripts/android-reverse.sh
scripts/up.sh                  # backend (file 09)
scripts/android-reverse.sh
```

**Sanity check from the device side:** open Chrome on the phone and go to `http://localhost:8000/api/restaurants`. You should see JSON. If not, fix this before debugging the app.

Then click **Run ▶** in Android Studio.

---

## Step 6: Test on mobile, end to end

### Scenario A: one device + rider script

1. Terminal: `scripts/rider-sim.sh` (bob is online).
2. App: **Log in** → Custom Tab → `alice` / `alice` → you land on the restaurant list.
3. Open **Dosa Junction**, add 2 items, **Place order**.
4. Watch the Tracking screen move through **PAID → RIDER_ASSIGNED → PICKED_UP**. The rider marker moves on the map, then the status reaches **DELIVERED**.

### Scenario B: two devices (customer + rider)

1. Start two emulators (or one emulator + one phone). Run `scripts/android-reverse.sh`; it reverses both.
2. Device 1: log in as **bob** → Rider screen → toggle **Online**.
3. Device 2: log in as **alice** and place an order.
4. Device 1: the order appears within ~4 s → **Picked up** → the rider marker moves on *both* screens → **Delivered**.

### Mobile test plan (tick these off)

| # | Test | How | Expected |
|---|---|---|---|
| M1 | PKCE login | Log in; check Keycloak admin → Sessions | Session listed for android-app |
| M2 | Token refresh | Stay in the app > 5 min, then browse | Works: silent refresh (logcat shows a `/token` call) |
| M3 | Refresh rotation | Two refreshes in logcat | Each uses a new refresh token |
| M4 | Logout revocation | Copy the access token from logcat *before* logout, log out, curl with it | `401 session revoked` |
| M5 | Idempotent order | Enable airplane mode right after tapping *Place order*, disable, tap again | Exactly **one** order in `GET /api/orders` |
| M6 | WebSocket reconnect | During tracking: `docker restart quickbite-realtime-svc-1` | The app reconnects (backoff), and later statuses still arrive |
| M7 | Background/foreground | Home button for 60 s, reopen | The stream resumes; the status is current |
| M8 | Backend chaos | PSP failure 100% (file 07), place an order | Tracking shows **CANCELLED** quickly |
| M9 | Wrong role | Log in as alice and craft a call to `/ws/rider` (e.g. websocat) | Socket closed: "riders only" |
| M11 | Pay with default card | Order without changing the ★ card | DELIVERED; tracking shows `VISA ••4242` |
| M12 | Declined card | Select `••0002` (or `••9995`), order | CANCELLED; "Reason: DECLINED: card_declined" / `insufficient_funds` |
| M13 | Add card via quick-fill | + Add card → tap `••0119` → Save → order | CANCELLED `PSP_UNAVAILABLE` after ~1 s of retries |
| M14 | Card validation | Type `4242 4242 4242 4241` → Save | Dialog shows "Invalid card number"; nothing saved |
| M15 | Amex CVC | Quick-fill Amex, change the CVC to 3 digits | "CVC must be 4 digits" |
| M16 | Sold-out item | Add the last menu item (marked "Sold out") and order | Error: "… is sold out" (409) |
| M17 | Second city | Tap **Mumbai** | 6 Mumbai restaurants |
| M10 | Cleartext policy | Change `API_BASE_URL` to another host over `http://` | Blocked by the network security config |

### Unit test example (JVM, no device): MockWebServer

`app/src/test/java/com/quickbite/app/ApiTest.kt`

```kotlin
package com.quickbite.app

import com.quickbite.app.net.QuickBiteApi
import com.quickbite.app.net.Network
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ApiTest {
    @Test fun `parses order ids as strings and ignores unknown fields`() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""
            {"id":"7322148834123456789","restaurantId":"r1","status":"PAID","total":400.0,"currency":"INR",
             "deliveryLat":12.9,"deliveryLon":77.6,"brandNewField":true}""".trimIndent()))
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(Network.json.asConverterFactory("application/json".toMediaType()))
            .build().create(QuickBiteApi::class.java)

        val order = api.order("7322148834123456789")

        assertEquals("7322148834123456789", order.id)          // no precision loss
        assertEquals("/api/orders/7322148834123456789", server.takeRequest().path)
        server.shutdown()
    }
}
```

---

## Verify

- Scenario A and Scenario B both reach **DELIVERED**, with a moving rider marker.
- Mobile tests M1–M10 behave as described.

**Checkpoint:**

1. The app decodes roles from the JWT to choose a screen. Why is that fine for UX, but never enough for security?
2. M5: which component (client or server) guarantees there is only one order, and how?
