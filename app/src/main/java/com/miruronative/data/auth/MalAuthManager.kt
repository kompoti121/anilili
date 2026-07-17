package com.miruronative.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.miruronative.diagnostics.DiagnosticsLog
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class MalTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis when the access token stops being usable. */
    val expiresAtMs: Long,
)

/**
 * MyAnimeList OAuth (Authorization Code + PKCE, `plain` — the only method MAL supports).
 * The login WebView intercepts the registered `https://localhost/?code=…` redirect, then
 * [exchangeCode] trades the code for tokens. MAL is a public client for Android apps, so
 * there is no client secret. Access tokens expire and refresh tokens rotate on every
 * refresh, so [freshAccessToken] must be used before each API call.
 */
object MalAuthManager {
    const val CLIENT_ID = "4ae5f8056db821737a4f40f8816177cd"
    private const val AUTHORIZE_ENDPOINT = "https://myanimelist.net/v1/oauth2/authorize"
    private const val TOKEN_ENDPOINT = "https://myanimelist.net/v1/oauth2/token"

    /** Refresh this long before the reported expiry so in-flight calls never race it. */
    private const val EXPIRY_MARGIN_MS = 5L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tokenStore: SecureTokenStore
    private var httpClient: OkHttpClient? = null
    private val refreshMutex = Mutex()
    private var pendingState: String? = null
    private var pendingVerifier: String? = null

    private val _tokens = MutableStateFlow<MalTokens?>(null)
    internal val tokens = _tokens.asStateFlow()

    /** UI-observable login state (token value itself stays internal). */
    val loggedIn = MutableStateFlow(false)

    fun init(context: Context, client: OkHttpClient) {
        tokenStore = SecureTokenStore(context.applicationContext, SecureTokenStore.KEY_MAL_TOKENS)
        httpClient = client
        _tokens.value = tokenStore.load()?.let { raw ->
            runCatching { json.decodeFromString(MalTokens.serializer(), raw) }.getOrNull()
        }
        loggedIn.value = _tokens.value != null
    }

    val isLoggedIn: Boolean get() = _tokens.value != null

    fun authorizeUrl(): String {
        val state = randomUrlSafe(24)
        // PKCE `plain`: the verifier IS the challenge. 32 random bytes → 43-char base64url,
        // inside MAL's required 43–128 length window.
        val verifier = randomUrlSafe(32)
        pendingState = state
        pendingVerifier = verifier
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", verifier)
            .appendQueryParameter("code_challenge_method", "plain")
            .build()
            .toString()
    }

    /** True for the registered `https://localhost/?code=…` redirect. */
    fun isRedirect(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.host == "localhost" && (uri.scheme == "https" || uri.scheme == "http") &&
            uri.getQueryParameter("code") != null
    }

    fun extractCode(url: String): String? {
        if (!isRedirect(url)) return null
        val uri = Uri.parse(url)
        val expectedState = pendingState ?: return null
        if (uri.getQueryParameter("state") != expectedState) return null
        return uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
    }

    /** Trades the authorization code for the first token pair; throws on failure. */
    suspend fun exchangeCode(code: String) = withContext(Dispatchers.IO) {
        val verifier = requireNotNull(pendingVerifier) { "No MAL login in progress" }
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", verifier)
            .build()
        store(requestTokens(body))
        pendingState = null
        pendingVerifier = null
        DiagnosticsLog.event("MAL login: token exchange succeeded")
    }

    /**
     * A currently valid access token, refreshing (and rotating the refresh token) if the stored
     * one is within [EXPIRY_MARGIN_MS] of expiry. Null when not logged in; on a failed refresh
     * with an expired access token the session is cleared (MAL refresh tokens do die).
     */
    suspend fun freshAccessToken(): String? {
        val current = _tokens.value ?: return null
        val now = System.currentTimeMillis()
        if (now < current.expiresAtMs - EXPIRY_MARGIN_MS) return current.accessToken
        return refreshMutex.withLock {
            val latest = _tokens.value ?: return@withLock null
            if (System.currentTimeMillis() < latest.expiresAtMs - EXPIRY_MARGIN_MS) {
                return@withLock latest.accessToken
            }
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", latest.refreshToken)
                    .build()
                runCatching { requestTokens(body) }
                    .onSuccess { store(it) }
                    .onFailure { error ->
                        DiagnosticsLog.throwable("MAL token refresh failed", error)
                        // Only drop the session when the old access token is truly dead;
                        // a transient network failure keeps the session for a later retry.
                        if (System.currentTimeMillis() >= latest.expiresAtMs) logout()
                    }
                _tokens.value?.accessToken
            }
        }
    }

    fun logout() {
        tokenStore.clear()
        _tokens.value = null
        loggedIn.value = false
        pendingState = null
        pendingVerifier = null
    }

    private fun requestTokens(body: FormBody): MalTokens {
        val client = requireNotNull(httpClient) { "MalAuthManager not initialised" }
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("MAL token endpoint HTTP ${response.code}: ${text.take(200)}")
            val parsed = json.decodeFromString(MalTokenResponse.serializer(), text)
            return MalTokens(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresAtMs = System.currentTimeMillis() + parsed.expiresIn * 1000,
            )
        }
    }

    private fun store(fresh: MalTokens) {
        tokenStore.save(json.encodeToString(MalTokens.serializer(), fresh))
        _tokens.value = fresh
        loggedIn.value = true
    }

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes)
        .also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
}

@Serializable
private data class MalTokenResponse(
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 3600,
)
