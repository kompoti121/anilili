package com.miruronative.data.model

import com.miruronative.data.ProviderCatalog
import kotlinx.serialization.Serializable

enum class Category(val api: String) {
    SUB("sub"),
    DUB("dub");

    companion object {
        fun from(api: String): Category = if (api.equals("dub", true)) DUB else SUB
    }
}

@Serializable
data class EpisodeItem(
    /** Raw pipe id — passed straight back to the `sources` endpoint. */
    val pipeId: String,
    val number: Double,
    val title: String?,
    val image: String?,
    val filler: Boolean,
) {
    val displayNumber: String get() = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
}

@Serializable
data class ProviderData(
    val name: String,
    val sub: List<EpisodeItem>,
    val dub: List<EpisodeItem>,
) {
    val isEmbed: Boolean get() = ProviderCatalog.isEmbed(name)
    fun episodes(category: Category): List<EpisodeItem> = if (category == Category.SUB) sub else dub
    fun hasCategory(category: Category): Boolean = episodes(category).isNotEmpty()
    val categories: List<Category> get() = buildList {
        if (sub.isNotEmpty()) add(Category.SUB)
        if (dub.isNotEmpty()) add(Category.DUB)
    }
}

@Serializable
data class EpisodesResult(val providers: List<ProviderData>) {
    val providerNames: List<String> get() = providers.map { it.name }
    fun provider(name: String): ProviderData? = providers.firstOrNull { it.name == name }
    val isEmpty: Boolean get() = providers.all { it.sub.isEmpty() && it.dub.isEmpty() }
}

data class StreamItem(
    val url: String,
    val type: String,
    val quality: String?,
    val audio: String?,
    val referer: String?,
    val isActive: Boolean,
    val width: Int?,
    val height: Int?,
) {
    val isHls: Boolean get() = type.equals("hls", true) || url.contains(".m3u8")
    val isEmbed: Boolean get() = type.equals("embed", true)
    val label: String get() = quality ?: height?.let { "${it}p" } ?: "auto"
}

data class SubtitleItem(val url: String, val label: String, val language: String)

@Serializable
data class SkipTimes(
    val introStart: Double?,
    val introEnd: Double?,
    val outroStart: Double?,
    val outroEnd: Double?,
)

data class SourcesResult(
    val streams: List<StreamItem>,
    val subtitles: List<SubtitleItem>,
    val skip: SkipTimes?,
    val download: String?,
) {
    val hlsStreams: List<StreamItem> get() = streams.filter { it.isHls }
    val embedStreams: List<StreamItem> get() = streams.filter { it.isEmbed }
}
