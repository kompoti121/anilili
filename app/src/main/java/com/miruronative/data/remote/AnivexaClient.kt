package com.miruronative.data.remote

import com.miruronative.data.ProviderCatalog
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.Media
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import com.miruronative.diagnostics.DiagnosticsLog
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** MegaPlay paths carry the episode's hash then the encode's: `/{episode}/{encode}/master.m3u8`. */
private val MEGAPLAY_ENCODE_TAG = Regex("""/([0-9a-f]{32})(?=/)""")

/** Provider fallbacks may ignore ordinary failures, but never consume coroutine cancellation. */
private inline fun <T> runProviderCatching(block: () -> T): Result<T> = runCatching(block).onFailure {
    if (it is CancellationException) throw it
}

/**
 * The encode's own hash in a MegaPlay URL, or null if the path is not shaped that way. Streams and
 * subtitle files that belong together carry the same one; a subtitle file quoting a different
 * encode than its stream is one cut for another release of the episode.
 */
internal fun megaPlayEncodeTag(url: String?): String? = url
    ?.let { MEGAPLAY_ENCODE_TAG.findAll(it).map { match -> match.groupValues[1] }.toList() }
    ?.getOrNull(1)

/**
 * How far a borrowed subtitle file sits from the stream it was handed to, judged from where each
 * encode's intro starts. Under a second is measurement noise in the provider's own marks; over a
 * minute is not a difference in head footage and is more likely mismatched episode data, so both
 * are treated as "leave the subtitles alone".
 */
internal fun borrowedSubtitleOffsetMs(dubIntroSeconds: Double?, subIntroSeconds: Double?): Long {
    val dubIntro = dubIntroSeconds ?: return 0L
    val subIntro = subIntroSeconds ?: return 0L
    val offsetMs = ((dubIntro - subIntro) * 1000.0).toLong()
    return if (kotlin.math.abs(offsetMs) in 1_000L..60_000L) offsetMs else 0L
}

/**
 * Native Android implementation of the additional providers that used to be reached through
 * Anivexa-API. Episode rows are built from AniList immediately; provider matching and stream
 * extraction happen lazily when a user starts an episode.
 */
class AnivexaClient(
    private val context: android.content.Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val aniList: AniListClient,
    private val cache: com.miruronative.data.cache.AppCache,
) {
    private data class Candidate(val slug: String, val title: String)
    private data class MegaPlayEmbed(val url: String, val referer: String, val skip: SkipTimes?)
    private data class MegaPlayResult(
        val embedUrl: String,
        val origin: String,
        val streams: List<StreamItem>,
        val subtitles: List<SubtitleItem>,
        val skip: SkipTimes?,
        val embedAvailable: Boolean,
    )

    // A provider host must never inherit the app-wide 45 second call budget. Coroutine timeouts
    // cancel the async calls below immediately; this client is also a hard backstop for the few
    // legacy provider parsers that still execute synchronous OkHttp calls.
    private val providerClient = client.newBuilder()
        .connectTimeout(PROVIDER_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROVIDER_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROVIDER_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val mediaCache = boundedMap<Int, Media>(100)
    private val identityCache = boundedMap<String, String>(250)
    private val allAnime = AllAnimeProvider(providerClient, json)
    private val animeKai = AnimeKaiProvider(providerClient)
    private val senshi = SenshiProvider(providerClient, json)
    private val aniBd = AniBdProvider(providerClient, json)
    private val kickAssAnime = KickAssAnimeProvider(providerClient, json)
    private val hanime = HanimeProvider(context, providerClient, json, cache)

    /** The device-side hanime library, also used to seed hentai results into search. */
    suspend fun hanimeCatalogue(): List<HanimeVideo> = hanime.catalogue()

    /** Pull a newer catalogue than the one shipped in the APK. */
    suspend fun refreshHanimeCatalogue() = hanime.refresh()

    suspend fun getEpisodes(
        anilistId: Int,
        seedMedia: Media? = null,
        // Adult-only providers drop out unless the viewer has asked to see adult content, so a
        // title that happens to match one by name never offers it uninvited.
        providers: List<String> = ProviderCatalog.anivexaProvidersFor(
            com.miruronative.data.settings.SettingsStore.hideAdultContent.value,
        ),
        providerTimeoutMs: Long = CATALOG_TIMEOUT_MS,
    ): EpisodesResult = withContext(Dispatchers.IO) {
        require(providerTimeoutMs > 0L) { "providerTimeoutMs must be positive" }
        // The caller (repository) has usually already fetched this AniList Media through the shared
        // 24h cache; reuse it so a cold catalog doesn't fire a second, rate-limit-exposed request.
        seedMedia?.let { mediaCache[anilistId] = it }
        val media = media(anilistId)
        val aired = media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
        val count = when {
            media.status == "RELEASING" && aired != null -> aired
            media.episodes != null && media.episodes > 0 -> media.episodes
            media.format == "MOVIE" -> 1
            else -> 1
        }

        coroutineScope {
            val lookups = providers.associateWith { provider ->
                async {
                    try {
                        val availability = withTimeoutOrNull(providerTimeoutMs) {
                            providerAvailability(provider, media, count)
                        }
                        if (availability == null) {
                            DiagnosticsLog.event(
                                "Native episode catalog timeout provider=$provider id=$anilistId " +
                                    "afterMs=$providerTimeoutMs",
                            )
                        }
                        availability
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagnosticsLog.throwable("Native episode catalog failed provider=$provider id=$anilistId", e)
                        null
                    }
                }
            }
            EpisodesResult(
                providers.mapNotNull { provider ->
                    lookups.getValue(provider).await()?.let { availability ->
                        ProviderData(
                            name = provider,
                            sub = episodeRows(provider, media, "sub", availability.sub),
                            dub = episodeRows(provider, media, "dub", availability.dub),
                        )
                    }
                },
            )
        }
    }

    suspend fun getSources(episodeId: String, seedMedia: Media? = null): SourcesResult {
        val request = NativeProviderParsers.episodeRequest(episodeId)
            ?: error("Invalid native provider episode id: $episodeId")
        seedMedia?.let { mediaCache[request.anilistId] = it }
        val media = media(request.anilistId)
        return when (request.provider) {
            "senshi" -> runInterruptible(Dispatchers.IO) { senshi.sources(media, request.audio, request.episode) }
            "anibd" -> runInterruptible(Dispatchers.IO) { aniBd.sources(media, request.audio, request.episode) }
            "anikoto" -> anikoto(media, request.audio, request.episode)
            "allanime" -> runInterruptible(Dispatchers.IO) { allAnime.sources(media, request.audio, request.episode) }
            "animekai" -> runInterruptible(Dispatchers.IO) { animeKai.sources(media, request.audio, request.episode) }
            "kaa" -> runInterruptible(Dispatchers.IO) { kickAssAnime.sources(media, request.audio, request.episode) }
            "reanime" -> reanime(media, request.audio, request.episode)
            "anizone" -> anizone(media, request.episode)
            "animegg" -> animegg(media, request.audio, request.episode)
            "anineko" -> anineko(media, request.audio, request.episode)
            "2dhive" -> twoDhive(media, request.audio, request.episode)
            "hanime" -> hanime.sources(media, request.episode)
            else -> error("Unsupported native provider: ${request.provider}")
        }
    }

    private suspend fun media(id: Int): Media = mediaCache[id]
        ?: aniList.animeInfo(id)?.also { mediaCache[id] = it }
        ?: error("Anime $id was not found on AniList")

    private fun episodeRows(provider: String, media: Media, audio: String, numbers: Set<Int>): List<EpisodeItem> {
        // Senshi's catalog carries real episode titles and filler flags; other providers don't.
        val meta = if (provider == "senshi") senshi.episodeMeta(media) else emptyMap()
        return numbers.sorted().map { number ->
            EpisodeItem(
                pipeId = "watch/$provider/${media.id}/$audio/$provider-$number",
                number = number.toDouble(),
                title = meta[number]?.title ?: "Episode $number",
                image = null,
                filler = meta[number]?.filler ?: false,
            )
        }
    }

    private suspend fun providerAvailability(provider: String, media: Media, count: Int): EpisodeAvailability = when (provider) {
        "senshi" -> runInterruptible(Dispatchers.IO) { senshi.episodeAvailability(media) }
        "anibd" -> runInterruptible(Dispatchers.IO) { aniBd.episodeAvailability(media) }
        "anikoto" -> anikotoAvailability(media, count)
        "allanime" -> runInterruptible(Dispatchers.IO) { allAnime.episodeAvailability(media) }
        "animekai" -> runInterruptible(Dispatchers.IO) { animeKai.episodeAvailability(media) }
        "kaa" -> runInterruptible(Dispatchers.IO) { kickAssAnime.episodeAvailability(media) }
        "reanime" -> reanimeAvailability(media)
        "anizone" -> anizoneAvailability(media, count)
        "animegg" -> animeGgAvailability(media)
        "anineko" -> aniNekoAvailability(media)
        "2dhive" -> twoDhiveAvailability(media, count)
        "hanime" -> hanime.episodeAvailability(media)
        else -> error("Unsupported native provider catalog: $provider")
    }

    private suspend fun anikotoAvailability(media: Media, count: Int): EpisodeAvailability = coroutineScope {
        suspend fun available(audio: String, episode: Int): Boolean {
            val ids = listOfNotNull(media.id, media.idMal).distinct()
            return ids.any { id ->
                val kind = if (id == media.id) "ani" else "mal"
                val url = "https://megaplay.buzz/stream/$kind/$id/$episode/$audio"
                val page = runProviderCatching {
                    getText(url, mapOf("Referer" to "https://hianimes.re/"))
                }.getOrDefault("")
                megaPlayPageAvailable(page)
            }
        }
        // Sub and dub availability are independent binary searches; probe them concurrently.
        val sub = async(Dispatchers.IO) { highestAvailable(count) { available("sub", it) } }
        val dub = async(Dispatchers.IO) { highestAvailable(count) { available("dub", it) } }
        EpisodeAvailability.counts(sub.await(), dub.await())
    }

    private suspend fun reanimeAvailability(media: Media): EpisodeAvailability {
        val base = "https://reanime.to"
        val results = titles(media).flatMap { query ->
            val root = runProviderCatching {
                getJson("$base/api/v1/search?q=${enc(query)}&limit=20&offset=0") as? JsonObject
            }.getOrNull()
            (root?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }.distinctBy { it.string("anime_id") }
        val chosen = results.maxByOrNull { item ->
            val title = item["title"] as? JsonObject
            titles(media).maxOfOrNull { expected ->
                maxOf(
                    NativeProviderParsers.titleSelectionScore(expected, title?.string("english").orEmpty()),
                    NativeProviderParsers.titleSelectionScore(expected, title?.string("romaji").orEmpty()),
                    NativeProviderParsers.titleSelectionScore(expected, title?.string("native").orEmpty()),
                )
            } ?: 0.0
        } ?: error("ReAnime match not found")
        val sub = chosen.number("subbed")?.toInt() ?: 0
        val dub = chosen.number("dubbed")?.toInt() ?: 0
        val availability = EpisodeAvailability.counts(sub, dub)
        return availability.copy(
            dub = availability.dub.filterNot { episode -> media.id to episode in REANIME_BAD_DUB_MUXES }.toSet(),
        )
    }

    private suspend fun anizoneAvailability(media: Media, airedCount: Int): EpisodeAvailability {
        val page = getText("https://anizone.to/anime/${anizoneSlug(media)}")
        val pageCount = NativeProviderParsers.labelledEpisodeCount(page)
            ?: error("AniZone returned no episode count")
        return EpisodeAvailability.counts(minOf(pageCount, airedCount), 0)
    }

    private suspend fun animeGgAvailability(media: Media): EpisodeAvailability {
        val page = getText("https://www.animegg.org/series/${animeGgSlug(media)}")
        return NativeProviderParsers.animeGgEpisodes(page).also {
            if (it.sub.isEmpty() && it.dub.isEmpty()) error("AnimeGG returned no episode catalog")
        }
    }

    private suspend fun aniNekoAvailability(media: Media): EpisodeAvailability {
        val page = getText("https://anineko.to/watch/${aniNekoSlug(media)}")
        return NativeProviderParsers.aniNekoEpisodes(page).also {
            if (it.sub.isEmpty() && it.dub.isEmpty()) error("AniNeko returned no episode catalog")
        }
    }

    private suspend fun twoDhiveAvailability(media: Media, fallbackCount: Int): EpisodeAvailability = coroutineScope {
        val malId = media.idMal ?: error("2Dhive needs a MyAnimeList id")
        suspend fun props(episode: Int): JsonObject? = runProviderCatching {
            extractAstroProps(getText("https://2dhive.com/episode?anime=$malId&ep_num=$episode"))
        }.getOrNull()
        val first = props(1)
        val total = first?.number("totalEpisodes")?.toInt()?.takeIf { it > 0 } ?: fallbackCount
        suspend fun available(audio: String, episode: Int): Boolean {
            val current = if (episode == 1) first else first?.let { props(episode) }
            val has2dHiveServer = (current?.get("servers") as? JsonArray).orEmpty().any { element ->
                val server = element as? JsonObject
                val isDub = (server?.get("dub") as? JsonPrimitive)?.booleanOrNull == true
                isDub == (audio == "dub")
            }
            return has2dHiveServer || run {
                val page = runProviderCatching {
                    getText(
                        "https://megaplay.buzz/stream/mal/$malId/$episode/$audio",
                        mapOf("Referer" to "https://2dhive.com/"),
                    )
                }.getOrDefault("")
                megaPlayPageAvailable(page)
            }
        }
        // Sub and dub availability are independent binary searches; probe them concurrently.
        val subDeferred = async(Dispatchers.IO) { highestAvailable(total) { episode -> available("sub", episode) } }
        val dubDeferred = async(Dispatchers.IO) { highestAvailable(total) { episode -> available("dub", episode) } }
        val sub = subDeferred.await()
        val dub = dubDeferred.await()
        if (sub == 0 && dub == 0) error("2Dhive returned no playable episode catalog")
        EpisodeAvailability.counts(sub, dub)
    }

    private suspend fun highestAvailable(limit: Int, available: suspend (Int) -> Boolean): Int {
        if (limit <= 0 || !available(1)) return 0
        if (limit == 1 || available(limit)) return limit
        var low = 1
        var high = limit - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (available(middle)) low = middle else high = middle - 1
        }
        return low
    }

    private fun megaPlayPageAvailable(page: String): Boolean = page.isNotBlank() &&
        !Regex("""<title>\s*Error\b|\berror-container\b""", RegexOption.IGNORE_CASE).containsMatchIn(page) &&
        (page.data("id") != null || Regex("""<iframe\b[^>]*src=["'][^"']+["']""", RegexOption.IGNORE_CASE).containsMatchIn(page))

    // ---- AniKoto / MegaPlay ---------------------------------------------------------------

    private suspend fun anikoto(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://megaplay.buzz"
        val primary = MegaPlayEmbed("$base/stream/ani/${media.id}/$episode/$audio", "https://hianimes.re/", null)
        val primaryResult = megaPlaySources(primary)
        val result = when {
            primaryResult.streams.any { it.isHls } -> primaryResult
            // MegaPlay hosts the full library but doesn't map every show to an AniList id.
            // Retry by MyAnimeList id (when known) before falling back to scraping the site.
            else -> anikotoMalSources(base, media, audio, episode)
                ?: runProviderCatching { anikotoSiteEmbed(media, audio, episode)?.let { megaPlaySources(it) } }
                    .getOrNull()
                    ?.takeIf { it.streams.any(StreamItem::isHls) }
                ?: primaryResult
        }

        val streams = result.streams.toMutableList()
        if (result.embedAvailable) {
            streams += stream(result.embedUrl, "embed", "MegaPlay embed", "${result.origin}/", active = streams.isEmpty())
        }
        val offsetMs = if (audio == "dub") megaPlaySubtitleOffsetMs(result, "https://hianimes.re/") else 0L
        return SourcesResult(
            streams.distinctBy { it.url },
            result.subtitles.distinctBy { it.url },
            result.skip,
            null,
            offsetMs,
        )
    }

    /**
     * MegaPlay hands its dub streams the *sub* encode's subtitle file for shows it holds no
     * dub-timed subtitles for — verified 2026-07-20 on How a Realist Hero Rebuilt the Kingdom,
     * whose dub encode's own subtitle folder 404s. The two encodes are not the same cut: the dub
     * carries extra footage at the head, 7-15 s depending on the episode, so every borrowed line
     * lands that much early. The gap between the two payloads' intro marks measures it — across
     * the four episodes checked it matched the difference in stream duration to within a second.
     *
     * Returns 0 for the ordinary case where the subtitles belong to the stream they arrived with,
     * so the extra request only costs the shows that actually borrow.
     */
    private suspend fun megaPlaySubtitleOffsetMs(dub: MegaPlayResult, referer: String): Long {
        val streamTag = megaPlayEncodeTag(dub.streams.firstOrNull(StreamItem::isHls)?.url) ?: return 0L
        val subtitleTag = megaPlayEncodeTag(dub.subtitles.firstOrNull()?.url) ?: return 0L
        if (streamTag == subtitleTag) return 0L
        val subUrl = dub.embedUrl.takeIf { it.endsWith("/dub") }?.dropLast(3)?.plus("sub") ?: return 0L
        val subResult = runProviderCatching { megaPlaySources(MegaPlayEmbed(subUrl, referer, null)) }.getOrNull()
            ?: return 0L
        // Only trust the intro marks once the borrowed file is confirmed to be this encode's own.
        if (megaPlayEncodeTag(subResult.streams.firstOrNull(StreamItem::isHls)?.url) != subtitleTag) return 0L
        val offsetMs = borrowedSubtitleOffsetMs(dub.skip?.introStart, subResult.skip?.introStart)
        DiagnosticsLog.event(
            "MegaPlay borrowed subtitles stream=$streamTag subs=$subtitleTag offsetMs=$offsetMs",
        )
        return offsetMs
    }

    /** MegaPlay by MyAnimeList id — covers shows that aren't mapped to an AniList id. */
    private suspend fun anikotoMalSources(base: String, media: Media, audio: String, episode: Int): MegaPlayResult? {
        val malId = media.idMal ?: return null
        val embed = MegaPlayEmbed("$base/stream/mal/$malId/$episode/$audio", "https://hianimes.re/", null)
        return runProviderCatching { megaPlaySources(embed) }.getOrNull()
            ?.takeIf { it.streams.any(StreamItem::isHls) }
    }

    private suspend fun megaPlaySources(embed: MegaPlayEmbed): MegaPlayResult {
        var embedUrl = embed.url
        var page = runProviderCatching { getText(embedUrl, mapOf("Referer" to embed.referer)) }.getOrDefault("")
        val iframe = Regex("""<iframe\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)
        if (page.data("id").isNullOrBlank() && !iframe.isNullOrBlank()) {
            embedUrl = NativeProviderParsers.absoluteUrl(embedUrl, iframe)
            page = runProviderCatching { getText(embedUrl, mapOf("Referer" to embed.referer)) }.getOrDefault(page)
        }

        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        val fileId = page.data("id")
        val origin = origin(embedUrl)
        var skip = embed.skip
        if (!fileId.isNullOrBlank()) {
            val source = runProviderCatching {
                getJson("$origin/stream/getSources?id=${enc(fileId)}&id=${enc(fileId)}", mapOf(
                    "Referer" to "$origin/",
                    "X-Requested-With" to "XMLHttpRequest",
                )) as? JsonObject
            }.getOrNull()
            val sourceFile = (source?.get("sources") as? JsonObject)?.string("file")
            if (!sourceFile.isNullOrBlank()) {
                streams += stream(sourceFile, "hls", "MegaPlay", "$origin/", active = true)
            }
            (source?.get("tracks") as? JsonArray).orEmpty().forEach { track ->
                val obj = track as? JsonObject ?: return@forEach
                val kind = obj.string("kind")?.lowercase()
                if (kind != null && kind != "captions" && kind != "subtitles") return@forEach
                val file = obj.string("file") ?: return@forEach
                val item = SubtitleItem(file, obj.string("label") ?: "Subtitle", language(obj.string("label")))
                val isDefault = (obj["default"] as? JsonPrimitive)?.booleanOrNull == true
                if (isDefault) subtitles.add(0, item) else subtitles += item
            }
            skip = skipTimes(source) ?: skip
        }
        return MegaPlayResult(
            embedUrl,
            origin,
            streams.distinctBy { it.url },
            subtitles.distinctBy { it.url },
            skip,
            embedAvailable = megaPlayPageAvailable(page),
        )
    }

    private suspend fun anikotoSiteEmbed(media: Media, audio: String, episode: Int): MegaPlayEmbed? {
        val base = "https://anikototv.to"
        val key = "anikoto-site:${media.id}"
        val cached = identityCache[key]?.let { listOf(Candidate(it, it.replace('-', ' '))) }.orEmpty()
        val candidates = (cached + anikotoSiteCandidates(base, media))
            .distinctBy { it.slug }
            .sortedByDescending { candidateScore(media, it) }
            .take(12)
        candidates.forEach { candidate ->
            val embed = runProviderCatching { anikotoSiteEmbedForSlug(base, candidate.slug, media, audio, episode) }
                .getOrNull()
            if (embed != null) {
                identityCache[key] = candidate.slug
                return embed
            }
        }
        return null
    }

    private suspend fun anikotoSiteCandidates(base: String, media: Media): List<Candidate> =
        titles(media).flatMap { query ->
            runProviderCatching {
                val search = getJson("$base/ajax/anime/search?keyword=${enc(query)}", xhr("$base/"))
                val html = htmlResult(search)
                Regex(
                    """<a\b([^>]*href=["'][^"']*/watch/[^"']+["'][^>]*)>([\s\S]*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ).findAll(html).mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val slug = Regex("""/watch/([^/?#"']+)""").find(href)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    Candidate(
                        slug,
                        NativeProviderParsers.stripTags(match.groupValues[2]).ifBlank { slug.replace('-', ' ') },
                    )
                }.toList()
            }.getOrDefault(emptyList())
        }.distinctBy { it.slug }

    private suspend fun anikotoSiteEmbedForSlug(
        base: String,
        slug: String,
        media: Media,
        audio: String,
        episode: Int,
    ): MegaPlayEmbed? {
        val watchUrl = "$base/watch/$slug/ep-$episode"
        val watch = getText(watchUrl, mapOf("Referer" to "$base/"))
        val animeId = Regex("""data-anime-id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
            .find(watch)?.groupValues?.get(1)
            ?: return null
        val episodes = getJson("$base/ajax/episode/list/$animeId", xhr(watchUrl)) as? JsonObject ?: return null
        val episodeTag = anikotoEpisodeTag(htmlResult(episodes), media, audio, episode) ?: return null
        val serverToken = NativeProviderParsers.attr(episodeTag, "data-ids").takeIf { it.isNotBlank() } ?: return null
        val servers = getJson("$base/ajax/server/list?servers=${enc(serverToken)}", xhr(watchUrl)) as? JsonObject
            ?: return null
        val linkId = anikotoServerLink(htmlResult(servers), audio) ?: return null
        val resolved = getJson("$base/ajax/server?get=${enc(linkId)}", xhr(watchUrl)) as? JsonObject ?: return null
        val result = (resolved["result"] as? JsonObject) ?: resolved
        val url = result.string("url") ?: return null
        return MegaPlayEmbed(url, "$base/", anikotoSkipData(result["skip_data"] as? JsonObject))
    }

    private fun anikotoEpisodeTag(html: String, media: Media, audio: String, episode: Int): String? {
        val expectedMal = media.idMal?.toString()
        val audioAttr = if (audio == "dub") "data-dub" else "data-sub"
        val tags = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.value }
            .filter { tag ->
                val num = NativeProviderParsers.attr(tag, "data-num").toIntOrNull()
                val slug = NativeProviderParsers.attr(tag, "data-slug").toIntOrNull()
                (num == episode || slug == episode) &&
                    NativeProviderParsers.attr(tag, audioAttr).let { it.isBlank() || it == "1" }
            }
            .toList()
        if (expectedMal != null) {
            return tags.firstOrNull { tag -> NativeProviderParsers.attr(tag, "data-mal") == expectedMal }
        }
        return tags.firstOrNull()
    }

    private fun anikotoServerLink(html: String, audio: String): String? {
        val items = Regex(
            """<li\b[^>]*\bdata-link-id=["'][^"']+["'][^>]*>[\s\S]*?</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).map { it.value }.toList()
        val compatible = items.filter { item ->
            NativeProviderParsers.attr(item, "data-type").let { it.isBlank() || it.equals(audio, true) }
        }.ifEmpty { items }
        val preferred = compatible.firstOrNull {
            NativeProviderParsers.attr(it, "data-sv-id").equals("e54", true) ||
                it.contains("Vidstream", ignoreCase = true)
        } ?: compatible.firstOrNull()
        return preferred?.let { NativeProviderParsers.attr(it, "data-link-id").takeIf { link -> link.isNotBlank() } }
    }

    private fun anikotoSkipData(root: JsonObject?): SkipTimes? {
        fun range(name: String): Pair<Double?, Double?> {
            val values = root?.get(name) as? JsonArray ?: return null to null
            return ((values.getOrNull(0) as? JsonPrimitive)?.doubleOrNull) to
                ((values.getOrNull(1) as? JsonPrimitive)?.doubleOrNull)
        }
        val intro = range("intro")
        val outro = range("outro")
        if (intro.first == null && intro.second == null && outro.first == null && outro.second == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }

    // ---- ReAnime --------------------------------------------------------------------------

    private suspend fun reanime(media: Media, audio: String, episode: Int): SourcesResult {
        if (audio == "dub" && (media.id to episode) in REANIME_BAD_DUB_MUXES) {
            error("ReAnime episode $episode DUB has a known unsynchronized audio track")
        }
        val base = "https://reanime.to"
        val flix = runProviderCatching { getJson("$base/api/flix/${media.id}/$episode") as? JsonObject }.getOrNull()
        val links = (flix?.get("servers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val accepted = if (audio == "dub") setOf("dub", "s-dub") else setOf("sub", "s-sub")
        val embeds = links.mapNotNull { link ->
            val kind = link.string("dataType")?.lowercase() ?: return@mapNotNull null
            if (kind !in accepted) return@mapNotNull null
            var url = link.string("dataLink") ?: return@mapNotNull null
            // ReAnime serves dual-audio embeds: sub and dub share one URL and the site
            // selects the English track by appending a=1 (audio track index).
            if (audio == "dub" && !url.contains(Regex("""[?&]a="""))) {
                url += if ('?' in url) "&a=1" else "?a=1"
            }
            ReanimeEmbed(url, link.string("serverName") ?: "ReAnime")
        }.distinctBy { it.url }.sortedWith(
            compareBy<ReanimeEmbed> { if (it.name.equals("HD-2", true)) 0 else 1 }
                .thenBy { it.name },
        )

        if (embeds.isEmpty()) error("ReAnime episode $episode has no ${audio.uppercase()} servers")

        val primary = embeds.first()
        val embedHtml = runProviderCatching { getText(primary.url, mapOf("Referer" to "$base/")) }.getOrDefault("")
        val subtitles = ReanimeFlixcloudParser.subtitles(embedHtml, audio)
        val skip = ReanimeFlixcloudParser.skip(embedHtml)
        val nativeHls = runProviderCatching { FlixcloudBridge.resolve(primary.url, "$base/") }
            .getOrNull()

        val streams = mutableListOf<StreamItem>()
        if (nativeHls != null) {
            streams += stream(
                nativeHls.url,
                "hls",
                "${primary.name} native",
                "${origin(primary.url)}/",
                active = true,
            ).copy(playlistKey = nativeHls.playlistKey)
        }
        embeds.take(4).forEachIndexed { index, embed ->
            streams += stream(
                embed.url,
                "embed",
                "${embed.name} embed",
                "$base/",
                active = streams.isEmpty() && index == 0,
            )
        }
        return SourcesResult(streams.distinctBy { it.url }, subtitles, skip, null)
    }

    private data class ReanimeEmbed(val url: String, val name: String)

    // ---- AniZone --------------------------------------------------------------------------

    private suspend fun anizone(media: Media, episode: Int): SourcesResult {
        val base = "https://anizone.to"
        val slug = anizoneSlug(media)
        val pageUrl = "$base/anime/$slug/$episode"
        val html = getText(pageUrl, mapOf("Referer" to "$base/anime/$slug"))
        val hls = Regex("""<media-player[^>]+src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(NativeProviderParsers::decodeEntities)
            ?: NativeProviderParsers.hlsUrls(html).firstOrNull()
            ?: error("AniZone episode $episode has no HLS stream")
        val subtitles = Regex("""<track\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
            val tag = match.value
            if (!NativeProviderParsers.attr(tag, "kind").equals("subtitles", true)) return@mapNotNull null
            val url = NativeProviderParsers.attr(tag, "src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = NativeProviderParsers.attr(tag, "label").ifBlank { "Subtitle" }
            SubtitleItem(NativeProviderParsers.absoluteUrl(base, url), label, NativeProviderParsers.attr(tag, "srclang").ifBlank { language(label) })
        }.toList()
        return SourcesResult(listOf(stream(hls, "hls", "AniZone", pageUrl, true)), subtitles, null, null)
    }

    private suspend fun anizoneSlug(media: Media): String {
        val base = "https://anizone.to"
        return resolveSlug("anizone", media) { query ->
            val html = getText("$base/anime?search=${enc(query)}")
            val animeLinks = Regex(
                """href=["'](?:https://anizone\.to)?/anime/([a-z0-9-]+)(?:[/?#][^"']*)?["']""",
                RegexOption.IGNORE_CASE,
            )
            val fallbackTitle = Regex(
                """getTitle\(this\.anmTitles,\s*'((?:\\'|[^'])*)'\)""",
                RegexOption.IGNORE_CASE,
            )
            animeLinks.findAll(html)
                .map { link ->
                    val start = (link.range.first - 3_000).coerceAtLeast(0)
                    val prefix = html.substring(start, link.range.first)
                    val title = fallbackTitle.findAll(prefix).lastOrNull()?.groupValues?.get(1)
                        ?.replace("\\'", "'")
                        ?: link.groupValues[1].replace('-', ' ')
                    Candidate(link.groupValues[1], title)
                }
                .distinctBy { it.slug }
                .toList()
        }
    }

    // ---- AnimeGG --------------------------------------------------------------------------

    private suspend fun animegg(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://www.animegg.org"
        val slug = animeGgSlug(media)
        val series = getText("$base/series/$slug")
        val episodeSlug = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE).findAll(series).mapNotNull { match ->
            val block = match.groupValues[1]
            if (!block.contains("anm_det_pop")) return@mapNotNull null
            val number = Regex("""<strong[^>]*>[\s\S]*?(\d+)\s*</strong>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (number != episode) return@mapNotNull null
            val tag = Regex("""<a\b[^>]*class=["'][^"']*anm_det_pop[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(block)?.value ?: return@mapNotNull null
            NativeProviderParsers.attr(tag, "href").trimStart('/').substringBefore('#')
        }.firstOrNull() ?: error("AnimeGG episode $episode was not found")

        val watchUrl = "$base/$episodeSlug"
        val watch = getText(watchUrl, mapOf("Referer" to base))
        val tabs = Regex("""<a\b[^>]*data-toggle=["']tab["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(watch)
            .mapNotNull { match ->
                val id = NativeProviderParsers.attr(match.value, "data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val version = NativeProviderParsers.attr(match.value, "data-version")
                val tabAudio = if (version.startsWith("dub", true)) "dub" else "sub"
                if (tabAudio != audio) return@mapNotNull null
                id to NativeProviderParsers.attr(match.value, "data-mirror").ifBlank { "AnimeGG" }
            }.toList()
        val streams = mutableListOf<StreamItem>()
        tabs.take(4).forEachIndexed { index, (id, server) ->
            val embed = "$base/embed/$id"
            val embedHtml = runProviderCatching { getText(embed, mapOf("Referer" to base)) }.getOrDefault("")
            val hlsUrls = NativeProviderParsers.hlsUrls(embedHtml)
            hlsUrls.forEach { url ->
                streams += stream(NativeProviderParsers.absoluteUrl(base, url), "hls", server, embed, streams.isEmpty())
            }
            // AnimeGG serves progressive MP4s through jwplayer (`file: "/play/.../video.mp4?for=..."`).
            val mp4Urls = Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(embedHtml).map { it.groupValues[1] }.toList()
            mp4Urls.forEach { rawUrl ->
                val url = NativeProviderParsers.absoluteUrl(base, rawUrl)
                streams += stream(url, "video", server, embed, streams.isEmpty())
            }
            if (animeGgEmbedCanPlay(embedHtml, hlsUrls.isNotEmpty() || mp4Urls.isNotEmpty())) {
                streams += stream(embed, "embed", "$server embed", embed, streams.isEmpty() && index == 0)
            }
        }
        return SourcesResult(streams.distinctBy { it.url }, emptyList(), null, null)
    }

    private suspend fun animeGgSlug(media: Media): String {
        val base = "https://www.animegg.org"
        return resolveSlug("animegg", media) { query ->
            val html = getText("$base/search/?q=${enc(query)}")
            Regex("""<a\b([^>]*class=["'][^"']*\bmse\b[^"']*["'][^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val id = Regex("""^/series/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    val strong = Regex("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE)
                        .find(match.groupValues[2])?.groupValues?.get(1)
                    Candidate(id, strong?.let(NativeProviderParsers::stripTags) ?: id.replace('-', ' '))
                }.toList()
        }
    }

    // ---- AniNeko --------------------------------------------------------------------------

    private suspend fun anineko(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://anineko.to"
        val slug = aniNekoSlug(media)
        val watchUrl = "$base/watch/$slug/ep-$episode"
        val html = getText(watchUrl, mapOf("Referer" to "$base/watch/$slug"))
        val embeds = mutableListOf<String>()
        Regex("""<div\b[^>]*class=["'][^"']*nv-server-grid[^"']*["'][^>]*data-id=["']([^"']+)["'][^>]*>([\s\S]*?)(?=<div\b[^>]*class=["'][^"']*nv-server-grid|$)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { panel ->
                val panelAudio = if (panel.groupValues[1].contains("dub", true)) "dub" else "sub"
                if (panelAudio == audio) {
                    Regex("""data-video=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .findAll(panel.groupValues[2]).forEach { embeds += NativeProviderParsers.decodeEntities(it.groupValues[1]) }
                }
            }
        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        embeds.distinct().take(4).forEachIndexed { index, embed ->
            val embedHtml = runProviderCatching { getText(embed, mapOf("Referer" to "$base/")) }.getOrDefault("")
            NativeProviderParsers.hlsUrls(embedHtml).forEach { hls ->
                streams += stream(hls, "hls", "AniNeko", embed, streams.isEmpty())
            }
            subtitles += embedQuerySubtitles(embed)
            streams += stream(embed, "embed", "AniNeko embed", embed, streams.isEmpty() && index == 0)
        }
        return SourcesResult(streams.distinctBy { it.url }, subtitles.distinctBy { it.url }, null, null)
    }

    private suspend fun aniNekoSlug(media: Media): String {
        val base = "https://anineko.to"
        return resolveSlug("anineko", media) { query ->
            val html = getText("$base/browser?keyword=${enc(query)}")
            Regex("""<a\b([^>]*class=["'][^"']*nv-anime-thumb[^"']*["'][^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val id = Regex("""/watch/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    Candidate(id, NativeProviderParsers.stripTags(match.groupValues[2]).ifBlank { id.replace('-', ' ') })
                }.toList()
        }
    }

    /**
     * AniNeko softsub servers pass their subtitle tracks to the embed player through the URL
     * (`?sub=<vtt>` or `?caption_1=<vtt>&sub_1=English`); recover them for native playback.
     */
    private fun embedQuerySubtitles(embedUrl: String): List<SubtitleItem> {
        val query = embedUrl.substringAfter('?', "")
        if (query.isBlank()) return emptyList()
        val params = query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }
        val byName = params.toMap()
        return params.mapNotNull { (key, value) ->
            if (!value.startsWith("http")) return@mapNotNull null
            val label = when {
                key == "sub" -> "English"
                key.startsWith("caption") -> byName["sub${key.removePrefix("caption")}"] ?: "Subtitle"
                else -> return@mapNotNull null
            }
            SubtitleItem(value, label, language(label))
        }
    }

    // ---- 2Dhive ---------------------------------------------------------------------------

    private suspend fun twoDhive(media: Media, audio: String, episode: Int): SourcesResult {
        val malId = media.idMal ?: error("2Dhive needs a MyAnimeList id")
        val base = "https://2dhive.com"
        val referer = "$base/episode?anime=$malId&ep_num=$episode"
        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()

        val props = runProviderCatching { extractAstroProps(getText(referer)) }.getOrNull()
        val matchingServers = (props?.get("servers") as? JsonArray).orEmpty().mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            val isDub = server["dub"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
            if (!server.string("server_name").equals("HAdfree", true) || isDub != (audio == "dub")) {
                return@mapNotNull null
            }
            server.string("slug")
        }
        matchingServers.take(3).forEach { slug ->
            val direct = runProviderCatching {
                (getJson("$base/api/hadfree?slug=${enc(slug)}", mapOf("Referer" to referer)) as? JsonObject)
                    ?.string("streamUrl")
            }.getOrNull()
            if (!direct.isNullOrBlank()) {
                val type = if (direct.contains(".m3u8", true)) "hls" else "video"
                streams += stream(direct, type, "2Dhive HAdfree", referer, streams.isEmpty())
            }
        }

        if (audio == "sub") {
            val hiAnime = runProviderCatching {
                getJson("$base/api/hianime?mal_id=$malId&ep_num=$episode", mapOf("Referer" to referer)) as? JsonObject
            }.getOrNull()
            val hls = hiAnime?.string("m3u8")
            val usableHls = hls?.takeIf { url ->
                runProviderCatching { getText(url, mapOf("Referer" to referer)).trimStart().startsWith("#EXTM3U") }
                    .getOrDefault(false)
            }
            usableHls?.let { streams += stream(it, "hls", "2Dhive hiAnime", referer, streams.isEmpty()) }
            if (usableHls != null) {
                hiAnime?.string("subtitle")?.let { subtitles += SubtitleItem(it, "English", "en") }
            }
        }
        val embed = "https://megaplay.buzz/stream/mal/$malId/$episode/${if (audio == "dub") "dub" else "sub"}"
        var subtitleOffsetMs = 0L
        runProviderCatching { megaPlaySources(MegaPlayEmbed(embed, referer, null)) }.getOrNull()?.let { megaPlay ->
            streams += megaPlay.streams
            subtitles += megaPlay.subtitles
            if (megaPlay.embedAvailable) {
                streams += stream(megaPlay.embedUrl, "embed", "2Dhive MegaPlay", referer, streams.isEmpty())
            }
            // The dub fallback here is MegaPlay's, so it borrows subtitles the same way AniKoto does.
            if (audio == "dub") subtitleOffsetMs = megaPlaySubtitleOffsetMs(megaPlay, referer)
        }
        return SourcesResult(streams, subtitles, null, null, subtitleOffsetMs)
    }

    // ---- Shared ---------------------------------------------------------------------------

    private suspend fun resolveSlug(
        provider: String,
        media: Media,
        search: suspend (String) -> List<Candidate>,
    ): String {
        val key = "$provider:${media.id}"
        identityCache[key]?.let { return it }
        val candidates = titles(media).flatMap { title ->
            runProviderCatching { search(title) }.getOrDefault(emptyList())
        }
            .distinctBy { it.slug }
        val chosen = candidates.maxByOrNull { candidateScore(media, it) }
            ?: error("${ProviderCatalog.label(provider)} match not found")
        if (candidateScore(media, chosen) < 0.28) error("${ProviderCatalog.label(provider)} title match was too weak")
        identityCache[key] = chosen.slug
        return chosen.slug
    }

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    private fun candidateScore(media: Media, candidate: Candidate): Double {
        var score = titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.title) } ?: 0.0
        score = maxOf(score, titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.slug) } ?: 0.0)
        media.seasonYear?.let { year ->
            val candidateYear = Regex("""\b(19|20)\d{2}\b""").find(candidate.title)?.value?.toIntOrNull()
            if (candidateYear != null) score *= if (candidateYear == year) 1.15 else 0.55
        }
        return score.coerceAtMost(1.0)
    }

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct()

    private fun xhr(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun htmlResult(element: JsonElement?): String = when (element) {
        is JsonObject -> element.string("html") ?: element.string("result") ?: htmlResult(element["result"])
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }

    private suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json,*/*")
            headers.forEach(builder::header)
            val call = providerClient.newCall(builder.get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            if (!response.isSuccessful) {
                                throw IOException("HTTP ${response.code} fetching $url")
                            }
                            response.body?.string().orEmpty()
                        }
                        continuation.resumeWith(Result.success(body))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(getText(url, headers + ("Accept" to "application/json, */*")))

    private fun stream(url: String, type: String, label: String, referer: String?, active: Boolean) = StreamItem(
        url = url,
        type = type,
        quality = label,
        audio = null,
        referer = referer,
        isActive = active,
        width = null,
        height = null,
    )

    private fun skipTimes(root: JsonObject?): SkipTimes? {
        fun range(name: String): Pair<Double?, Double?> {
            val obj = root?.get(name) as? JsonObject
            return (obj?.number("start")) to (obj?.number("end"))
        }
        val intro = range("intro")
        val outro = range("outro")
        if (intro.first == null && intro.second == null && outro.first == null && outro.second == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }

    /** Decode Astro's compact `[type, value]` serialization used in 2Dhive component props. */
    private fun extractAstroProps(html: String): JsonObject? {
        val marker = html.indexOf("prefetchedHls")
        if (marker < 0) return null
        val valueStart = html.lastIndexOf("props=\"", marker).takeIf { it >= 0 }?.plus(7) ?: return null
        val valueEnd = html.indexOf('"', valueStart).takeIf { it > valueStart } ?: return null
        val raw = NativeProviderParsers.decodeEntities(html.substring(valueStart, valueEnd))
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        return JsonObject(root.mapValues { (_, value) -> astroDecode(value) })
    }

    private fun astroDecode(value: JsonElement): JsonElement {
        if (value !is JsonArray || value.size < 2) {
            return if (value is JsonObject) JsonObject(value.mapValues { astroDecode(it.value) }) else value
        }
        val type = (value[0] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return value
        val data = value[1]
        return when (type) {
            0 -> if (data is JsonObject) JsonObject(data.mapValues { astroDecode(it.value) }) else data
            1 -> if (data is JsonArray) JsonArray(data.map(::astroDecode)) else data
            else -> data
        }
    }

    private fun language(label: String?): String = when (label?.lowercase()) {
        "english", "en" -> "en"
        "japanese", "ja" -> "ja"
        "french", "fr" -> "fr"
        "german", "de" -> "de"
        "spanish", "es" -> "es"
        "portuguese", "pt" -> "pt"
        else -> "und"
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(url.substringBefore('/', ""))

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun String.data(name: String): String? = Regex("""data-${Regex.escape(name)}=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.get(1)?.let(NativeProviderParsers::decodeEntities)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val CATALOG_TIMEOUT_MS = 15_000L
        private const val PROVIDER_HTTP_CALL_TIMEOUT_MS = 15_000L
        private val REANIME_BAD_DUB_MUXES = setOf(113415 to 2)
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

internal fun animeGgEmbedCanPlay(html: String, hasExtractedMedia: Boolean): Boolean {
    if (hasExtractedMedia) return true
    if (html.isBlank()) return false
    val explicitlyEmpty = Regex(
        """\b(?:var|let|const)\s+videoSources\s*=\s*\[\s*]\s*;?""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(html)
    if (explicitlyEmpty) return false
    return html.contains("jwplayer", ignoreCase = true) ||
        Regex("""<iframe\b[^>]*src=["'][^"']+["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)
}
