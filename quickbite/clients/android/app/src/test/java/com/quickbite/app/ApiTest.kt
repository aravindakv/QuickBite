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