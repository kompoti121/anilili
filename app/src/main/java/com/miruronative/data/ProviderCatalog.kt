package com.miruronative.data

/**
 * Provider classification across both streaming backends:
 * - **Miruro** pipe providers (via the on-device WebView bridge)
 * - **Anivexa** providers (via an Anivexa-API instance over HTTP)
 * A provider name is globally unique, so routing is by name.
 */
object ProviderCatalog {
    enum class Source { MIRURO, ANIVEXA }

    // Miruro pipe providers.
    private val miruroOrder = listOf(
        "bonk", "kiwi", "pewe", "bee", "ally", "moo", "hop", // native HLS
        "nun", "bun", "twin", "cog", "telli",                 // iframe embeds
    )
    private val miruroEmbed = setOf("nun", "bun", "twin", "cog", "telli")

    // Anivexa providers we query (reliable, self-hosted sources).
    val anivexaProviders = listOf(
        "senshi", "anibd", "anikoto", "kaa", "allanime", "animekai", "reanime", "anizone", "animegg", "anineko", "2dhive",
    )

    // Providers whose resolution drives the hidden resolver WebView rather than plain HTTP. That
    // page runs a real player, so resolving one competes for the hardware video decoder with
    // whatever is already on screen — background validation skips these on TV.
    val webViewResolverProviders = setOf("reanime")

    // The consistently quick Anivexa lookups (API-backed, not full-page scrapers). Raced as an
    // early partial catalog when the Miruro pipe is down or slow, so playback never waits for
    // the 15-second stragglers; the remaining providers still merge in behind.
    val fastAnivexaProviders = listOf("senshi", "anibd", "anikoto", "kaa")

    // Providers that consistently resolve and start quickly: the Miruro pipe's native HLS set
    // (one pipe call away) and the API-backed Anivexa lookups. Embeds and full-page scrapers
    // are excluded. Drives the ⚡ badge and the speed-first sort below.
    val fastProviders: Set<String> =
        (miruroOrder - miruroEmbed).toSet() + fastAnivexaProviders

    fun isFast(provider: String): Boolean = provider in fastProviders

    // Default row order: bonk (Miruro pipe) then anibd (Anivexa) lead as the two default
    // sources — independent backends, both fast and reliable — with senshi right behind.
    // After the leaders, fast providers sort before embeds and slow scrapers so the quick
    // options are always at the top of the server list. A user's saved favourite provider
    // always overrides this order.
    private val leaders = listOf("bonk", "anibd", "senshi")
    private val order = leaders +
        (miruroOrder + anivexaProviders).filterNot { it in leaders }
            .sortedByDescending { it in fastProviders }

    fun sourceOf(provider: String): Source =
        if (provider in anivexaProviders) Source.ANIVEXA else Source.MIRURO

    /** Only Miruro has provider-level iframe embeds; Anivexa decides embed per-stream. */
    fun isEmbed(provider: String): Boolean = provider in miruroEmbed
    fun isNative(provider: String): Boolean = !isEmbed(provider)

    fun sortKey(provider: String): Int =
        order.indexOf(provider).let { if (it >= 0) it else Int.MAX_VALUE }

    fun label(provider: String): String = when (provider) {
        "anibd" -> "AniBD"
        "2dhive" -> "2Dhive"
        "allanime" -> "AllAnime"
        "animekai" -> "AnimeKai"
        "kaa" -> "KickAssAnime"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
}
