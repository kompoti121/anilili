package com.miruronative.data

import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.remote.AniListClient
import com.miruronative.data.remote.AnivexaClient
import com.miruronative.data.remote.PipeClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point the UI talks to. Combines AniList metadata with two streaming backends —
 * the Miruro pipe (WebView bridge) and Anivexa-API (HTTP) — and caches episode lists per source.
 */
class MiruroRepository(
    private val aniList: AniListClient,
    private val pipe: PipeClient,
    private val anivexa: AnivexaClient,
) {
    // ---- discovery (AniList) ----
    suspend fun trending(page: Int = 1): MediaPage = aniList.collection("TRENDING_DESC", page = page, perPage = 30)
    suspend fun popular(page: Int = 1): MediaPage = aniList.collection("POPULARITY_DESC", page = page, perPage = 30)
    suspend fun topRated(page: Int = 1): MediaPage = aniList.collection("SCORE_DESC", page = page, perPage = 30)
    suspend fun recentlyReleased(page: Int = 1): MediaPage =
        aniList.collection("START_DATE_DESC", status = "RELEASING", page = page, perPage = 30)

    suspend fun airing(page: Int = 1): MediaPage =
        aniList.collection("POPULARITY_DESC", status = "RELEASING", page = page, perPage = 40)

    suspend fun schedule(dayOffset: Int): List<AiringSchedule> {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.now(zone).plusDays(dayOffset.toLong())
        val start = day.atStartOfDay(zone).toEpochSecond()
        val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        return aniList.airingSchedule(start, end)
    }

    suspend fun search(query: String, page: Int = 1): MediaPage = aniList.search(query, page)

    // ---- authenticated (AniList login) ----
    suspend fun viewer() = aniList.viewer()
    suspend fun userAnimeList(userId: Int) = aniList.userAnimeList(userId)
    suspend fun saveAniListProgress(mediaId: Int, progress: Int, completed: Boolean = false) =
        aniList.saveMediaListEntry(mediaId, if (completed) "COMPLETED" else "CURRENT", progress)

    private val infoCache = HashMap<Int, Media>()
    suspend fun animeInfo(id: Int): Media? {
        infoCache[id]?.let { return it }
        return aniList.animeInfo(id)?.also { infoCache[id] = it }
    }

    // ---- streaming (two backends, cached per source) ----
    private val miruroCache = HashMap<Int, EpisodesResult>()
    private val anivexaCache = HashMap<Int, EpisodesResult>()
    private val mutex = Mutex()

    /** Fast source — the Miruro pipe. */
    suspend fun miruroEpisodes(anilistId: Int): EpisodesResult {
        mutex.withLock { miruroCache[anilistId] }?.let { return it }
        val result = pipe.getEpisodes(anilistId)
        mutex.withLock { miruroCache[anilistId] = result }
        return result
    }

    /** Extra sources — Anivexa-API (can be slower; loaded in the background by the detail screen). */
    suspend fun anivexaEpisodes(anilistId: Int): EpisodesResult {
        mutex.withLock { anivexaCache[anilistId] }?.let { return it }
        val result = anivexa.getEpisodes(anilistId)
        mutex.withLock { anivexaCache[anilistId] = result }
        return result
    }

    /** Merged view of both sources — used where the full provider list is needed (watch screen). */
    suspend fun episodes(anilistId: Int): EpisodesResult = coroutineScope {
        val miruro = async {
            runCatching { miruroEpisodes(anilistId) }.getOrDefault(EpisodesResult(emptyList()))
        }
        val anivexa = async {
            runCatching { anivexaEpisodes(anilistId) }.getOrDefault(EpisodesResult(emptyList()))
        }
        mergeProviders(miruro.await(), anivexa.await())
    }

    fun mergeProviders(a: EpisodesResult, b: EpisodesResult): EpisodesResult =
        EpisodesResult((a.providers + b.providers).sortedBy { ProviderCatalog.sortKey(it.name) })

    suspend fun sources(
        pipeId: String,
        provider: String,
        category: Category,
        anilistId: Int,
    ): SourcesResult = when (ProviderCatalog.sourceOf(provider)) {
        ProviderCatalog.Source.ANIVEXA -> anivexa.getSources(pipeId)
        ProviderCatalog.Source.MIRURO -> pipe.getSources(pipeId, provider, category, anilistId)
    }

    data class ResolvedSources(val sources: SourcesResult, val provider: String)

    /**
     * Resolve a playable stream for [number], trying [preferred] first and then other providers
     * (fast Miruro ones before slower Anivexa scrapers) until one actually returns a stream.
     * This is the multi-source fallback: picking a server that has no copy silently rolls over.
     */
    suspend fun resolveSources(
        anilistId: Int,
        number: Double,
        preferred: String,
        category: Category,
        excludedProviders: Set<String> = emptySet(),
        maxAttempts: Int = 5,
    ): ResolvedSources? {
        val merged = episodes(anilistId)
        val ordered = (listOf(preferred) + merged.providerNames.sortedBy { ProviderCatalog.sortKey(it) })
            .distinct()

        var attempts = 0
        for (name in ordered) {
            if (attempts >= maxAttempts) break
            if (name in excludedProviders) continue
            val provider = merged.provider(name) ?: continue
            val ep = provider.episodes(category).firstOrNull { it.number == number } ?: continue
            attempts++
            val result = runCatching { sources(ep.pipeId, name, category, anilistId) }.getOrNull()
            if (result != null && result.streams.isNotEmpty()) {
                return ResolvedSources(result, name)
            }
        }
        return null
    }
}
