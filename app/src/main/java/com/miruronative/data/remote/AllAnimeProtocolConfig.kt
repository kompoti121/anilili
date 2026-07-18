package com.miruronative.data.remote

/**
 * Versioned values copied from a specific MKissa web-client deployment.
 *
 * Keeping them together makes a client rotation an explicit, reviewable update instead of
 * scattering a new build ID, request hash, and crypto mask through the provider.
 */
internal data class AllAnimeProtocolVersion(
    val version: String,
    val buildId: String,
    val currentSourcesHash: String,
    val legacySourcesHash: String,
    val cryptoMask: String,
    val currentApiOrigin: String,
    val legacyApi: String,
    val apiReferer: String,
    val apiOrigin: String,
    val playerReferer: String,
) {
    val currentApi: String get() = "$currentApiOrigin/api"
    val bootstrapUrl: String get() = "$currentApiOrigin/client-crypto/v1/bootstrap?buildId=$buildId"
}

internal object AllAnimeProtocolConfig {
    val active = AllAnimeProtocolVersion(
        version = "mkissa-build-44",
        buildId = "44",
        currentSourcesHash = "09caca435564416f37d5c78256c8e6e517007c3006529857e84ba2466bfcbea6",
        legacySourcesHash = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec",
        cryptoMask = "cd7f14dbf40734836eb46eb14758e49ef9d81e61686d84d467b2e32063ef4af9",
        currentApiOrigin = "https://api.mkissa.net",
        legacyApi = "https://api.allanime.day/api",
        apiReferer = "https://youtu-chan.com/",
        apiOrigin = "https://youtu-chan.com",
        playerReferer = "https://allanime.day/",
    )
}
