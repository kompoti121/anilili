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
    val anivexaProviders = listOf("anikoto", "reanime", "anizone", "animegg", "anineko", "2dhive")

    private val order = miruroOrder + anivexaProviders

    fun sourceOf(provider: String): Source =
        if (provider in anivexaProviders) Source.ANIVEXA else Source.MIRURO

    /** Only Miruro has provider-level iframe embeds; Anivexa decides embed per-stream. */
    fun isEmbed(provider: String): Boolean = provider in miruroEmbed
    fun isNative(provider: String): Boolean = !isEmbed(provider)

    fun sortKey(provider: String): Int =
        order.indexOf(provider).let { if (it >= 0) it else Int.MAX_VALUE }

    fun label(provider: String): String = when (provider) {
        "2dhive" -> "2Dhive"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
}
