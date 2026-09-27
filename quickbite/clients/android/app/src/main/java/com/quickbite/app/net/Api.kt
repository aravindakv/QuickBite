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
    @GET("api/orders/{id}/track") suspend fun track(@Path("id") id: String): TrackDto
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
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.BASIC
        })
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