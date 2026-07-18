package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import java.net.URI
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct, browser-free KickAssAnime integration. KAA exposes a small JSON catalog API and serves
 * adaptive, multi-audio HLS from its own krussdomi CDN. The CDN requires both Referer and Origin;
 * [StreamItem.referer] is therefore the player origin so PlaybackService derives the right pair.
 */
internal class KickAssAnimeProvider(
    private val client: OkHttpClient,
    private val json: Json,
) {
    internal data class Show(
        val slug: String,
        val title: String,
        val locales: Set<String>,
        val year: Int?,
        val type: String?,
    )

    private val showCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, Show>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Show>?): Boolean = size > 100
        },
    )

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val show = resolveShow(media)
        val sub = show.locales.takeIf { it.isEmpty() || ORIGINAL_LOCALE in it }
            ?.let { episodeNumbers(show.slug, ORIGINAL_LOCALE) }
            .orEmpty()
        val dub = show.locales.takeIf { DUB_LOCALE in it }
            ?.let { episodeNumbers(show.slug, DUB_LOCALE) }
            .orEmpty()
        return EpisodeAvailability(sub, dub)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val show = resolveShow(media)
        val wantsDub = audio.equals("dub", ignoreCase = true)
        val locale = if (wantsDub) DUB_LOCALE else ORIGINAL_LOCALE
        if (show.locales.isNotEmpty() && locale !in show.locales) {
            error("KickAssAnime has no ${if (wantsDub) "English dub" else "Japanese audio"} for ${show.title}")
        }

        val episodePage = getJson(episodesUrl(show.slug, episode, locale))
        val row = KickAssAnimeCodec.episode(episodePage, episode)
            ?: error("KickAssAnime episode $episode ($locale) was not found")
        val detail = getJson("$API/show/${show.slug}/episode/ep-$episode-${row.slug}")
        val playerUrl = KickAssAnimeCodec.vidStreamingUrl(detail)
            ?: error("KickAssAnime episode $episode has no native HLS server")
        val playerId = KickAssAnimeCodec.playerId(playerUrl)
            ?: error("KickAssAnime player did not expose a media id")
        val subtitles = runCatching {
            KickAssAnimeCodec.subtitles(getText(playerUrl, "$BASE/"))
        }.getOrDefault(emptyList())

        val label = if (wantsDub) "KickAssAnime English" else "KickAssAnime Japanese"
        return SourcesResult(
            streams = listOf(
                StreamItem(
                    url = "$HLS_BASE/$playerId/master.m3u8",
                    type = "hls",
                    quality = label,
                    audio = if (wantsDub) "dub" else "sub",
                    referer = "$PLAYER_ORIGIN/",
                    isActive = true,
                    width = null,
                    height = null,
                ),
            ),
            subtitles = subtitles,
            skip = null,
            download = null,
        )
    }

    private fun resolveShow(media: Media): Show {
        showCache[media.id]?.let { return it }
        val titles = listOfNotNull(media.title.english, media.title.romaji, media.title.native)
            .filter(String::isNotBlank)
            .distinct()
        val candidates = titles.flatMap { title ->
            runCatching { search(title) }.getOrDefault(emptyList())
        }.distinctBy { it.slug }
        val chosen = candidates.maxByOrNull { candidate -> candidateScore(media, candidate) }
            ?: error("KickAssAnime match not found")
        if (candidateScore(media, chosen) < MIN_TITLE_SCORE) {
            error("KickAssAnime title match was too weak")
        }
        showCache[media.id] = chosen
        return chosen
    }

    private fun search(query: String): List<Show> {
        val body = buildJsonObject { put("query", query) }.toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = requestBuilder("$API/fsearch", "$BASE/")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val root = executeJson(request)
        return KickAssAnimeCodec.searchResults(root)
    }

    private fun candidateScore(media: Media, candidate: Show): Double {
        val titles = listOfNotNull(media.title.english, media.title.romaji, media.title.native)
            .filter(String::isNotBlank)
        var score = titles.maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.title) } ?: 0.0
        media.seasonYear?.let { expected ->
            candidate.year?.let { actual -> score *= if (expected == actual) 1.15 else 0.55 }
        }
        val expectedType = media.format?.lowercase()
        val actualType = candidate.type?.lowercase()
        if (expectedType != null && actualType != null) {
            val compatible = expectedType == actualType || (expectedType.startsWith("tv") && actualType == "tv")
            if (!compatible) score *= 0.7
        }
        return score.coerceAtMost(1.0)
    }

    private fun episodeNumbers(slug: String, locale: String): Set<Int> =
        KickAssAnimeCodec.episodeNumbers(getJson(episodesUrl(slug, 1, locale)))

    private fun episodesUrl(slug: String, episode: Int, locale: String): String =
        "$API/show/$slug/episodes?ep=$episode&lang=$locale"

    private fun getJson(url: String): JsonElement = executeJson(requestBuilder(url, "$BASE/").get().build())

    private fun executeJson(request: Request): JsonElement =
        json.parseToJsonElement(execute(request))

    private fun getText(url: String, referer: String): String =
        execute(requestBuilder(url, referer).get().build())

    private fun requestBuilder(url: String, referer: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json,text/html,*/*")
        .header("Referer", referer)

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("HTTP ${response.code} fetching ${request.url}")
        body
    }

    companion object {
        internal const val BASE = "https://kaa.lt"
        internal const val PLAYER_ORIGIN = "https://krussdomi.com"
        private const val API = "$BASE/api"
        private const val HLS_BASE = "https://hls.krussdomi.com/manifest"
        private const val ORIGINAL_LOCALE = "ja-JP"
        private const val DUB_LOCALE = "en-US"
        private const val MIN_TITLE_SCORE = 0.28
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal object KickAssAnimeCodec {
    data class Episode(val number: Int, val slug: String, val title: String?)

    fun searchResults(root: JsonElement): List<KickAssAnimeProvider.Show> {
        val result = (root as? JsonObject)?.get("result") as? JsonArray ?: return emptyList()
        return result.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val slug = row.string("slug") ?: return@mapNotNull null
            val title = row.string("title_en") ?: row.string("title") ?: slug
            val locales = (row["locales"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet()
            KickAssAnimeProvider.Show(
                slug = slug,
                title = title,
                locales = locales,
                year = row.number("year")?.toInt(),
                type = row.string("type"),
            )
        }
    }

    fun episodeNumbers(root: JsonElement): Set<Int> {
        val obj = root as? JsonObject ?: return emptySet()
        val fromPages = (obj["pages"] as? JsonArray).orEmpty()
            .flatMap { page ->
                (((page as? JsonObject)?.get("eps") as? JsonArray).orEmpty())
                    .mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }
            }
            .filter { it > 0 }
            .toSet()
        if (fromPages.isNotEmpty()) return fromPages
        return episodes(root).map(Episode::number).filter { it > 0 }.toSet()
    }

    fun episode(root: JsonElement, number: Int): Episode? =
        episodes(root).firstOrNull { it.number == number }

    fun vidStreamingUrl(root: JsonElement): String? {
        val servers = ((root as? JsonObject)?.get("servers") as? JsonArray).orEmpty()
        return servers.mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { server ->
            val name = server.string("name").orEmpty()
            val src = server.string("src")
            src?.takeIf { name.equals("VidStreaming", true) || it.contains("source=vidstream", true) }
        }
    }

    fun playerId(playerUrl: String): String? = runCatching {
        URI(playerUrl).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
            val parts = pair.split('=', limit = 2)
            parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "id" && it.isNotBlank() }
        }
    }.getOrNull()

    fun subtitles(html: String): List<SubtitleItem> {
        val decoded = NativeProviderParsers.decodeEntities(html)
        val pattern = Regex(
            "\"language\":\\[0,\"([^\"]*)\"\\],\"name\":\\[0,\"([^\"]*)\"\\],\"src\":\\[0,\"([^\"]+\\.vtt)\"\\]",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(decoded).map { match ->
            val language = match.groupValues[1].ifBlank { "und" }
            val label = match.groupValues[2].ifBlank { language }
            SubtitleItem(match.groupValues[3], label, language)
        }.distinctBy(SubtitleItem::url).toList()
    }

    private fun episodes(root: JsonElement): List<Episode> {
        val result = ((root as? JsonObject)?.get("result") as? JsonArray).orEmpty()
        return result.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val number = row.number("episode_number")?.toInt() ?: return@mapNotNull null
            val slug = row.string("slug") ?: return@mapNotNull null
            Episode(number, slug, row.string("title"))
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(name: String): Double? =
        (this[name] as? JsonPrimitive)?.doubleOrNull
}
