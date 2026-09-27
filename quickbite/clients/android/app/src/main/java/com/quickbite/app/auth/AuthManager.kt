package com.quickbite.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
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
        context,
        AppAuthConfiguration.Builder()
            .setConnectionBuilder(connectionBuilder)
            .setSkipIssuerHttpsCheck(BuildConfig.DEBUG)   // <-- add this
            .build()
    )

    @Volatile var state: AuthState = store.get(KEY)?.let { AuthState.jsonDeserialize(it) } ?: AuthState()
        private set

    val isLoggedIn: Boolean get() = state.isAuthorized

    suspend fun loginIntent(): Intent {
        val config = discover()
        val request = AuthorizationRequest.Builder(
            config, BuildConfig.CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI)
        ).setScopes("openid", "profile")
            // Otherwise Keycloak silently re-authenticates from its own browser SSO cookie,
            // skipping the credential form entirely (that cookie outlives our local logout/clear).
            .setPrompt("login")
            .build()   // PKCE code_verifier/challenge generated here automatically
        return service.getAuthorizationRequestIntent(request)
    }

    /** RP-initiated logout: ends the Keycloak browser session, not just our local tokens.
     *  Without this, a fresh [loginIntent] can silently re-auth from that still-live session. */
    suspend fun endSessionIntent(): Intent {
        val config = discover()
        val request = EndSessionRequest.Builder(config)
            .setIdTokenHint(requireNotNull(state.idToken) { "endSessionIntent() called while not logged in" })
            .setPostLogoutRedirectUri(Uri.parse(LOGOUT_REDIRECT_URI))
            .build()
        return service.getEndSessionRequestIntent(request)
    }

    /** Best-effort diagnostics for the end-session browser round-trip; local state is cleared regardless. */
    fun logEndSessionResult(data: Intent?) {
        val error = data?.let { AuthorizationException.fromIntent(it) } ?: return
        Log.e("QuickBiteAuth", "end-session redirect returned an error: ${error.type}/${error.code} ${error.errorDescription}")
    }

    suspend fun completeLogin(data: Intent) {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        state.update(response, error)
        if (response == null) {
            val e = error ?: IllegalStateException("Login cancelled")
            Log.e("QuickBiteAuth", "auth failed: ${(e as? AuthorizationException)?.let { "${it.type}/${it.code} ${it.error} ${it.errorDescription}" } ?: e.message}")
            throw e
        }
        val request = response.createTokenExchangeRequest()
        val tokens = suspendCancellableCoroutine { cont ->
            // DEBUG ONLY: the local Keycloak is http://, and AppAuth requires an https issuer
            // before it will validate an ID token. The token is still signed and still validated
            // by every backend service (issuer + audience + signature), so nothing is trusted blindly.
            val action = NoClientAuthentication.INSTANCE
            service.performTokenRequest(request, action) { r, e ->
                if (r != null) cont.resume(r) else {
                    Log.e("QuickBiteAuth",
                        "token exchange failed: type=${e?.type} code=${e?.code} desc=${e?.errorDescription} cause=${e?.cause?.message}")
                    cont.resumeWithException(e ?: IllegalStateException("token exchange failed"))
                }
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
        const val LOGOUT_REDIRECT_URI = "com.quickbite.app:/logout"   // must match realm's post.logout.redirect.uris
    }
}