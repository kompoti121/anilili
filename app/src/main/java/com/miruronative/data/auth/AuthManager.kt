package com.miruronative.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.miruronative.data.reminder.AniListNotificationPushManager
import com.miruronative.data.reminder.ReleaseSyncScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull

/**
 * AniList OAuth (Implicit Grant). AniList redirects to `http://localhost/#access_token=…`; the
 * login WebView intercepts that URL before Android networking tries to open localhost. No client
 * secret is stored in the app. Tokens are long-lived (~1 year; AniList has no refresh).
 */
object AuthManager {
    const val CLIENT_ID = "45552"
    const val REDIRECT = "http://localhost"
    private const val AUTHORIZE_ENDPOINT = "https://anilist.co/api/v2/oauth/authorize"

    private lateinit var tokenStore: SecureTokenStore
    private lateinit var appContext: Context
    private var pendingOAuthState: String? = null

    private val _token = MutableStateFlow<String?>(null)
    val token = _token.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        tokenStore = SecureTokenStore(appContext)
        _token.value = tokenStore.load()?.takeUnless(::isJwtExpired)
        if (_token.value == null) tokenStore.clear()
    }

    fun current(): String? {
        val value = _token.value ?: return null
        if (!isJwtExpired(value)) return value
        clearToken(scheduleSync = false)
        return null
    }

    val isLoggedIn: Boolean get() = current() != null
    fun viewerId(): Int? = current()?.let(::jwtSubject)

    fun authorizeUrl(): String {
        val state = ByteArray(24).also(SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        pendingOAuthState = state
        // AniList's implicit flow uses the redirect registered for the client. Supplying the same
        // redirect explicitly currently makes its post-login grant step fail with unsupported_grant_type.
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    fun setToken(token: String) {
        val normalized = token.trim()
        require(normalized.isNotEmpty()) { "AniList returned an empty token" }
        tokenStore.save(normalized)
        _token.value = normalized
        pendingOAuthState = null
        AniListNotificationPushManager.clearDelivered(appContext)
        ReleaseSyncScheduler.runNow(appContext)
    }

    fun logout() {
        clearToken(scheduleSync = true)
    }

    /** True once a redirect URL carries the token; extract it with [extractToken]. */
    fun isRedirect(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host == "localhost" && uri.fragment?.contains("access_token=") == true
    }

    fun extractToken(url: String): String? {
        if (!isRedirect(url)) return null
        val uri = Uri.parse(url)
        val fragment = Uri.parse("$REDIRECT/?${uri.fragment.orEmpty()}")
        val expectedState = pendingOAuthState ?: return null
        if (fragment.getQueryParameter("state") != expectedState) return null
        return fragment.getQueryParameter("access_token")?.takeIf { it.isNotBlank() }
    }

    private fun clearToken(scheduleSync: Boolean) {
        tokenStore.clear()
        _token.value = null
        pendingOAuthState = null
        AniListNotificationPushManager.clearDelivered(appContext)
        if (scheduleSync) ReleaseSyncScheduler.runNow(appContext)
    }
}

private val jwtJson = Json { ignoreUnknownKeys = true }

internal fun isJwtExpired(token: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean {
    val expiration = jwtLongClaim(token, "exp") ?: return false
    return expiration <= nowEpochSeconds + TOKEN_EXPIRY_SKEW_SECONDS
}

internal fun jwtSubject(token: String): Int? = jwtLongClaim(token, "sub")?.toInt()

private fun jwtLongClaim(token: String, name: String): Long? = runCatching {
    val payload = token.split('.').getOrNull(1) ?: return@runCatching null
    val decoded = java.util.Base64.getUrlDecoder().decode(payload)
    val value = jwtJson.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject[name]?.jsonPrimitive
    value?.longOrNull ?: value?.intOrNull?.toLong()
}.getOrNull()

private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
