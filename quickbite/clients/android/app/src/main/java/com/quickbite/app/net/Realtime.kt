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