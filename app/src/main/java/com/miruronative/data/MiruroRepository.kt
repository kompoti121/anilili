package com.miruronative.data

import com.miruronative.data.model.AiringSchedule
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaPage
import com.miruronative.data.model.HomeCollections
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.cache.AppCache
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.AnimeStat
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.model.UserAvatar
import com.miruronative.data.model.Viewer
import com.miruronative.data.model.ViewerStatistics
import com.miruronative.data.remote.AniListClient
import com.miruronative.data.remote.AniSkipClient
import com.miruronative.data.remote.AnivexaClient
import com.miruronative.data.remote.JikanClient
import com.miruronative.data.remote.MalClient
import com.miruronative.data.remote.MediaListProgressSnapshot
import com.miruronative.data.remote.PipeClient
import com.miruronative.data.remote.planMediaListProgressUpdate
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Keeps fallback attempts spread across independent backends. Providers from one backend often
 * fail together, so exhausting the attempt budget on adjacent aliases defeats fallback entirely.
 */
internal fun providerAttemptOrder(preferred: String, providerNames: List<String>): List<String> {
    val available = (listOf(preferred) + providerNames.sortedBy { ProviderCatalog.sortKey(it) }).distinct()
    val preferredSource = ProviderCatalog.sourceOf(preferred)
    val sameSource = available.filter { it != preferred && ProviderCatalog.sourceOf(it) == preferredSource }
    val otherSource = available.filter { ProviderCatalog.sourceOf(it) != preferredSource }

    return buildList {
        add(preferred)
        repeat(maxOf(sameSource.size, otherSource.size)) { index ->
            otherSource.getOrNull(index)?.let(::add)
            sameSource.getOrNull(index)?.let(::add)
        }
    }
}

/**
 * Single entry point the UI talks to. Combines AniList metadata with two streaming backends —
 * the Miruro pipe (WebView bridge) and Anivexa-API (HTTP) — and caches episode lists per source.
 */
class MiruroRepository(
    private val aniList: AniListClient,
    private val pipe: PipeClient,
    private val anivexa: AnivexaClient,
    private val jikan: JikanClient,
    private val aniSkip: AniSkipClient,
    private val mal: MalClient,
    private val cache: AppCache,
) {
    /** User preference: keep hentai out of every browsing surface. */
    private val hideAdult: Boolean get() = SettingsStore.hideAdultContent.value

    // Bounds the related-seasons walk's AniList fan-out so it can't monopolise the shared,
    // rate-limited connection pool (5/host) and stall playback-critical metadata/catalog lookups.
    private val seriesFetchGate = Semaphore(SERIES_FETCH_CONCURRENCY)

    // ---- discovery (AniList) ----
    suspend fun homeCollections(force: Boolean = false): HomeCollections {
        val adultHidden = hideAdult
        val collections = cache.getOrFetch(
            key = "home:v2:${if (adultHidden) "sfw" else "all"}",
            serializer = HomeCollections.serializer(),
            ttlMs = HOME_TTL,
            forceRefresh = force,
        ) { aniList.homeCollections(adultHidden) }
        if (!adultHidden) return collections
        return collections.copy(
            spotlight = collections.spotlight.filterNot { it.isAdult },
            newest = collections.newest.filterNot { it.isAdult },
            popular = collections.popular.filterNot { it.isAdult },
            movies = collections.movies.filterNot { it.isAdult },
            topRated = collections.topRated.filterNot { it.isAdult },
        )
    }

    suspend fun trending(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("trending:$page", COLLECTION_TTL, force) {
        aniList.collection("TRENDING_DESC", page = page, perPage = 30, hideAdult = hideAdult)
    }
    suspend fun popular(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("popular:$page", COLLECTION_TTL, force) {
        aniList.collection("POPULARITY_DESC", page = page, perPage = 30, hideAdult = hideAdult)
    }
    suspend fun topRated(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("top:$page", COLLECTION_TTL, force) {
        aniList.collection("SCORE_DESC", page = page, perPage = 30, hideAdult = hideAdult)
    }
    suspend fun recentlyReleased(page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("recent:$page", AIRING_TTL, force) {
            aniList.collection("START_DATE_DESC", status = "RELEASING", page = page, perPage = 30, hideAdult = hideAdult)
        }

    suspend fun airing(page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("airing:$page", AIRING_TTL, force) {
            aniList.collection("POPULARITY_DESC", status = "RELEASING", page = page, perPage = 40, hideAdult = hideAdult)
        }

    suspend fun schedule(dayOffset: Int, force: Boolean = false): List<AiringSchedule> {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.now(zone).plusDays(dayOffset.toLong())
        val start = day.atStartOfDay(zone).toEpochSecond()
        val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
        // Cached unfiltered and filtered at read time, so toggling the setting applies instantly.
        val schedules = cache.getOrFetch(
            key = "schedule:$day",
            serializer = ListSerializer(AiringSchedule.serializer()),
            ttlMs = SCHEDULE_TTL,
            forceRefresh = force,
        ) { aniList.airingSchedule(start, end) }
        return if (hideAdult) schedules.filterNot { it.media?.isAdult == true } else schedules
    }

    suspend fun search(query: String, page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("search:${query.trim().lowercase()}:$page", SEARCH_TTL, force) { aniList.search(query, page, hideAdult = hideAdult) }

    suspend fun discover(filters: DiscoverFilters, page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("discover:${filters.cacheKey()}:$page", COLLECTION_TTL, force) { aniList.discover(filters, page, hideAdult = hideAdult) }

    suspend fun discoverOptions(): DiscoverOptions {
        val adultHidden = hideAdult
        return cache.getOrFetch(
            key = "discover-options:${if (adultHidden) "sfw" else "all"}",
            serializer = DiscoverOptions.serializer(),
            ttlMs = OPTIONS_TTL,
        ) { aniList.discoverOptions(adultHidden) }
    }

    // ---- authenticated (AniList login) ----
    suspend fun viewer() = aniList.viewer()
    suspend fun notifications(markAllRead: Boolean = false) = aniList.notifications(markAllRead)
    suspend fun favouriteAnime() = aniList.favouriteAnime()
    suspend fun userAnimeList(userId: Int) = aniList.userAnimeList(userId)
    suspend fun saveAniListProgress(mediaId: Int, progress: Int, totalEpisodes: Int?) =
        aniList.syncMediaListProgress(mediaId, progress, totalEpisodes)
    suspend fun syncSavedAnime(mediaId: Int, saved: Boolean) = aniList.syncSavedAnime(mediaId, saved)
    suspend fun syncSavedAnime(mediaIds: Collection<Int>) {
        val viewerId = AuthManager.viewerId() ?: aniList.viewer()?.id ?: return
        aniList.syncSavedAnime(mediaIds, viewerId)
    }

    // ---- authenticated (MyAnimeList login) ----

    /** The MAL account shaped like an AniList [Viewer] so the profile UI stays shared. */
    suspend fun malViewer(): Viewer {
        val user = mal.viewer()
        val stats = user.animeStatistics
        return Viewer(
            id = user.id,
            name = user.name,
            avatar = UserAvatar(large = user.picture),
            bannerImage = null,
            createdAt = user.joinedAt?.let { raw ->
                runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
            },
            statistics = ViewerStatistics(
                anime = AnimeStat(
                    count = stats?.numItems ?: 0,
                    minutesWatched = ((stats?.numDaysWatched ?: 0.0) * 1440).toLong(),
                    meanScore = stats?.meanScore ?: 0.0,
                ),
            ),
        )
    }

    /**
     * The MAL anime list as AniList-shaped [MediaListEntry]s. MAL ids are joined back to
     * AniList media in day-cached batches of 50; the odd title AniList doesn't know is dropped.
     */
    suspend fun malAnimeList(): List<MediaListEntry> {
        val entries = mal.animeList()
        val byMalId = mutableMapOf<Int, Media>()
        entries.map { it.malId }.distinct().sorted().chunked(50).forEach { chunk ->
            cache.getOrFetch(
                key = "malmap:v1:${chunk.hashCode()}",
                serializer = ListSerializer(Media.serializer()),
                ttlMs = MAL_MAP_TTL,
            ) { aniList.mediaByMalIds(chunk) }
                .forEach { media -> media.idMal?.let { byMalId[it] = media } }
        }
        return entries.mapNotNull { entry ->
            val media = byMalId[entry.malId] ?: return@mapNotNull null
            MediaListEntry(
                id = entry.malId,
                progress = entry.progress,
                score = entry.score,
                status = entry.status,
                media = media,
            )
        }
    }

    /** Mirrors the device Save button on MAL without damaging an existing list state. */
    suspend fun malSyncSavedAnime(anilistId: Int, saved: Boolean) {
        val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
        if (malId == null) {
            DiagnosticsLog.event("MAL saved sync skipped id=$anilistId: no MAL id on AniList")
            return
        }
        val current = mal.listStatus(malId)
        when {
            saved && current == null -> {
                mal.updateListStatus(malId, status = "plan_to_watch")
                DiagnosticsLog.event("MAL saved sync added malId=$malId (id=$anilistId)")
            }
            !saved && current?.status == "plan_to_watch" -> {
                mal.deleteListEntry(malId)
                DiagnosticsLog.event("MAL saved sync removed malId=$malId (id=$anilistId)")
            }
            else -> DiagnosticsLog.event(
                "MAL saved sync no-op malId=$malId (id=$anilistId) saved=$saved currentStatus=${current?.status}",
            )
        }
    }

    /** Batch push: one list fetch, then add only the titles MAL doesn't have yet. */
    suspend fun malSyncSavedAnime(anilistIds: Collection<Int>) {
        if (anilistIds.isEmpty()) return
        val onMal = mal.animeList().map { it.malId }.toSet()
        anilistIds.forEach { id ->
            runCatching {
                val malId = animeInfo(id)?.idMal?.takeIf { it > 0 }
                when {
                    malId == null -> DiagnosticsLog.event("MAL saved sync skipped id=$id: no MAL id on AniList")
                    malId !in onMal -> {
                        mal.updateListStatus(malId, status = "plan_to_watch")
                        DiagnosticsLog.event("MAL saved sync added malId=$malId (id=$id)")
                    }
                }
            }.onFailure { DiagnosticsLog.throwable("MAL saved sync failed id=$id", it) }
        }
    }

    /** Update MAL watched progress with the same non-regression policy as the AniList sync. */
    suspend fun saveMalProgress(anilistId: Int, progress: Int, totalEpisodes: Int?) {
        val media = animeInfo(anilistId)
        val malId = media?.idMal?.takeIf { it > 0 } ?: return
        val current = mal.listStatus(malId)?.let {
            MediaListProgressSnapshot(id = malId, status = MalClient.anilistStatus(it), progress = it.numEpisodesWatched)
        }
        val update = planMediaListProgressUpdate(current, progress, totalEpisodes ?: media.episodes) ?: return
        mal.updateListStatus(malId, progress = update.progress, status = update.status?.let(MalClient::malStatus))
    }

    /** MAL filler episode numbers via Jikan; cached a week, empty on failure or no MAL id. */
    suspend fun fillerEpisodes(malId: Int?): Set<Int> {
        if (malId == null || malId <= 0) return emptySet()
        return cache.getOrFetch(
            key = "filler:$malId",
            serializer = SetSerializer(Int.serializer()),
            ttlMs = OPTIONS_TTL,
        ) { withContext(Dispatchers.IO) { jikan.fillerEpisodes(malId) } }
    }

    /** Community intro/outro markers via AniSkip; cached, null when unknown or on failure. */
    suspend fun skipTimes(anilistId: Int, episode: Double): SkipTimes? {
        if (episode % 1.0 != 0.0 || episode < 1) return null
        val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 } ?: return null
        return cache.getOrFetch(
            key = "aniskip:$malId:${episode.toInt()}",
            serializer = SkipTimes.serializer().nullable,
            ttlMs = OPTIONS_TTL,
        ) { withContext(Dispatchers.IO) { runCatching { aniSkip.skipTimes(malId, episode.toInt()) }.getOrNull() } }
    }

    suspend fun animeInfo(id: Int, force: Boolean = false): Media? = cache.getOrFetch(
        key = "anime:v3:$id",
        serializer = Media.serializer().nullable,
        ttlMs = INFO_TTL,
        forceRefresh = force,
    ) { aniList.animeInfo(id) }

    /** Walks AniList's PREQUEL/SEQUEL chain so every season is reachable from one detail page. */
    suspend fun animeSeries(root: Media): List<Media> = coroutineScope {
        val found = linkedMapOf(root.id to root)
        val expanded = mutableSetOf<Int>()
        var frontier = listOf(root)
        var depth = 0

        while (frontier.isNotEmpty() && found.size < MAX_SERIES_ENTRIES && depth < MAX_SERIES_DEPTH) {
            val batch = frontier
                .filter { expanded.add(it.id) }
                .take(MAX_SERIES_ENTRIES - found.size + 1)
            if (batch.isEmpty()) break

            val detailed = batch.map { media ->
                async {
                    if (media.id == root.id && media.relations.edges.isNotEmpty()) {
                        media
                    } else {
                        seriesFetchGate.withPermit { runCatching { animeInfo(media.id) }.getOrNull() } ?: media
                    }
                }
            }.awaitAll()

            val next = mutableListOf<Media>()
            detailed.forEach { media ->
                found[media.id] = media
                media.seasonNeighbors().forEach { neighbor ->
                    if (hideAdult && neighbor.isAdult) return@forEach
                    if (neighbor.id !in found && found.size < MAX_SERIES_ENTRIES) {
                        found[neighbor.id] = neighbor
                    }
                    if (neighbor.id !in expanded) next += neighbor
                }
            }
            frontier = next.distinctBy(Media::id)
            depth++
        }

        found.values.sortedWith(
            compareBy<Media>(
                { it.startDate?.year ?: it.seasonYear ?: Int.MAX_VALUE },
                { it.startDate?.month ?: Int.MAX_VALUE },
                { it.startDate?.day ?: Int.MAX_VALUE },
                { it.id },
            ),
        )
    }

    // ---- streaming (two backends, cached per source) ----
    /** Fast source — the Miruro pipe. */
    suspend fun miruroEpisodes(anilistId: Int, force: Boolean = false): EpisodesResult = cache.getOrFetch(
        key = "episodes:v3:miruro:$anilistId",
        serializer = EpisodesResult.serializer(),
        ttlMs = EPISODES_TTL,
        forceRefresh = force,
    ) {
        pipe.getEpisodes(anilistId).also {
            check(!it.isEmpty) { "Miruro returned no episode providers" }
        }
    }.withFillerMarks(anilistId)

    /** Extra sources — Anivexa-API (can be slower; loaded in the background by the detail screen). */
    suspend fun anivexaEpisodes(anilistId: Int, force: Boolean = false): EpisodesResult {
        // Fetched here (outside the episodes cache lock — the striped mutexes are not reentrant) so
        // the Anivexa catalog reuses the shared AniList Media instead of re-requesting it itself.
        val seed = runCatching { animeInfo(anilistId) }.getOrNull()
        return cache.getOrFetch(
            key = "episodes:v4:anivexa:$anilistId",
            serializer = EpisodesResult.serializer(),
            ttlMs = EPISODES_TTL,
            forceRefresh = force,
        ) {
            anivexa.getEpisodes(anilistId, seed).also {
                check(!it.isEmpty) { "Anivexa returned no episode providers" }
            }
        }.withFillerMarks(anilistId)
    }

    /**
     * Applies MAL filler flags to every provider's episode list. Providers rarely carry filler
     * data themselves; Jikan is authoritative and cached, and a timeout keeps a slow first
     * fetch from delaying episode loading.
     */
    private suspend fun EpisodesResult.withFillerMarks(anilistId: Int): EpisodesResult {
        if (isEmpty) return this
        val fillers = kotlinx.coroutines.withTimeoutOrNull(FILLER_FETCH_TIMEOUT_MS) {
            runCatching { fillerEpisodes(animeInfo(anilistId)?.idMal) }.getOrDefault(emptySet())
        } ?: return this
        if (fillers.isEmpty()) return this
        fun mark(episodes: List<EpisodeItem>): List<EpisodeItem> = episodes.map { episode ->
            val isFiller = episode.number % 1.0 == 0.0 && episode.number.toInt() in fillers
            if (isFiller && !episode.filler) episode.copy(filler = true) else episode
        }
        return EpisodesResult(providers.map { it.copy(sub = mark(it.sub), dub = mark(it.dub)) })
    }

    /** Merged view of both sources — used where the full provider list is needed (watch screen). */
    suspend fun episodes(anilistId: Int): EpisodesResult = coroutineScope {
        val miruro = async {
            runCatching { miruroEpisodes(anilistId) }
                .onFailure { DiagnosticsLog.throwable("Miruro episodes failed id=$anilistId", it) }
                .getOrDefault(EpisodesResult(emptyList()))
        }
        val anivexa = async {
            runCatching { anivexaEpisodes(anilistId) }
                .onFailure { DiagnosticsLog.throwable("Anivexa episodes failed id=$anilistId", it) }
                .getOrDefault(EpisodesResult(emptyList()))
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
        ProviderCatalog.Source.ANIVEXA -> anivexa.getSources(pipeId, animeInfo(anilistId))
        ProviderCatalog.Source.MIRURO -> pipe.getSources(pipeId, provider, category, anilistId)
    }

    data class ResolvedSources(val sources: SourcesResult, val provider: String)

    /**
     * Resolve a playable stream for [number] within the supplied [episodes] catalog, trying
     * [preferred] first and then other providers (fast Miruro ones before slower Anivexa
     * scrapers) until one actually returns a stream. This is the multi-source fallback: picking
     * a server that has no copy silently rolls over. The caller owns the catalog so playback can
     * start on the fast Miruro set before the Anivexa providers have finished loading.
     */
    suspend fun resolveSources(
        anilistId: Int,
        number: Double,
        preferred: String,
        category: Category,
        episodes: EpisodesResult,
        excludedProviders: Set<String> = emptySet(),
        maxAttempts: Int = 5,
    ): ResolvedSources? {
        val ordered = providerAttemptOrder(preferred, episodes.providerNames)

        var attempts = 0
        for (name in ordered) {
            if (attempts >= maxAttempts) break
            if (name in excludedProviders) continue
            val provider = episodes.provider(name) ?: continue
            val ep = provider.episodes(category).firstOrNull { it.number == number } ?: continue
            attempts++
            val result = runCatching { sources(ep.pipeId, name, category, anilistId) }
                .onFailure {
                    DiagnosticsLog.throwable(
                        "Source resolve failed provider=$name id=$anilistId episode=$number",
                        it,
                    )
                }
                .getOrNull()
            if (result != null && result.streams.isNotEmpty()) {
                return ResolvedSources(result, name)
            }
            if (result != null) {
                DiagnosticsLog.event(
                    "Source resolve empty provider=$name id=$anilistId episode=$number",
                )
            }
        }
        return null
    }

    private suspend fun mediaPage(
        key: String,
        ttlMs: Long,
        force: Boolean = false,
        fetch: suspend () -> MediaPage,
    ): MediaPage {
        // The adult preference is part of the cache key: filtered and unfiltered pages differ.
        val hideAdult = hideAdult
        val page = cache.getOrFetch(
            key = "media:v2:$key:${if (hideAdult) "sfw" else "all"}",
            serializer = MediaPage.serializer(),
            ttlMs = ttlMs,
            forceRefresh = force,
            fetch = fetch,
        )
        // Safety net over the server-side isAdult filter (covers surfaces AniList can't filter).
        return if (hideAdult) page.copy(items = page.items.filterNot { it.isAdult }) else page
    }

    private fun DiscoverFilters.cacheKey(): String = listOf(
        query.trim().lowercase(),
        genres.sorted().joinToString(","),
        tags.sorted().joinToString(","),
        year.orEmpty(),
        status.orEmpty(),
        format.orEmpty(),
        minimumScore.orEmpty(),
        sort,
    ).joinToString("|")

    private fun Any?.orEmpty(): String = this?.toString() ?: ""

    private companion object {
        const val MAX_SERIES_ENTRIES = 16
        const val MAX_SERIES_DEPTH = 8
        const val SERIES_FETCH_CONCURRENCY = 2
        const val SCHEDULE_TTL = 15L * 60 * 1000
        const val HOME_TTL = 30L * 60 * 1000
        /** MAL id → AniList media join; ids never remap, so a day is conservative. */
        const val MAL_MAP_TTL = 24L * 60 * 60 * 1000
        const val SEARCH_TTL = 30L * 60 * 1000
        const val AIRING_TTL = 30L * 60 * 1000
        const val COLLECTION_TTL = 4L * 60 * 60 * 1000
        const val EPISODES_TTL = 2L * 60 * 60 * 1000
        const val FILLER_FETCH_TIMEOUT_MS = 3_500L
        const val INFO_TTL = 24L * 60 * 60 * 1000
        const val OPTIONS_TTL = 7L * 24 * 60 * 60 * 1000
    }
}

internal fun Media.seasonNeighbors(): List<Media> = relations.edges
    .asSequence()
    .filter { it.relationType == "PREQUEL" || it.relationType == "SEQUEL" }
    .mapNotNull { it.node }
    .distinctBy(Media::id)
    .toList()
