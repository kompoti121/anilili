package com.miruronative.ui.watch

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.auth.AccountService
import com.miruronative.data.settings.SettingsStore
import com.miruronative.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long a ready stream waits on the AniSkip lookup before starting without markers. */
private const val ANISKIP_WAIT_MS = 2_500L

/**
 * How long the initial load waits on the Miruro pipe before proceeding with the fast Anivexa
 * catalog alone. A healthy warm pipe answers well under this; a Cloudflare-dead one takes 15 s+,
 * which used to be dead time on the loading screen. Miruro still merges in if it answers later.
 */
private const val MIRURO_CATALOG_WAIT_MS = 8_000L

data class WatchData(
    val episodes: List<EpisodeItem>,
    val currentIndex: Int,
    val provider: String,
    val category: Category,
    val sourceOptions: List<WatchSourceOption>,
    val anilistId: Int,
    val sources: SourcesResult,
    val chosenStream: StreamItem?,
    val seriesTitle: String,
    val artworkUrl: String?,
    val startPositionMs: Long = 0,
    val playbackGeneration: Int = 0,
    val preferredProvider: String = DEFAULT_PREFERRED_PROVIDER,
    val isResolving: Boolean = false,
    /** The fast Miruro sources are shown; the slower Anivexa servers are still loading in. */
    val isLoadingMoreSources: Boolean = false,
    val notice: String? = null,
) {
    val current: EpisodeItem get() = episodes[currentIndex]
    val hasNext: Boolean get() = currentIndex < episodes.lastIndex
    val hasPrev: Boolean get() = currentIndex > 0
}

data class WatchSourceOption(
    val provider: String,
    val category: Category,
    val hasCurrentEpisode: Boolean,
    val episodeCount: Int,
)

class WatchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<WatchData>>(UiState.Loading)
    val state = _state.asStateFlow()

    /** Live status line for the initial loading screen ("main source is down, trying others…"). */
    private val _loadingStatus = MutableStateFlow<String?>(null)
    val loadingStatus = _loadingStatus.asStateFlow()

    private var anilistId = 0
    private var category = Category.SUB
    private var globalPreferredProvider = DEFAULT_PREFERRED_PROVIDER
    private var preferred = ""
    private var spine: List<EpisodeItem> = emptyList()
    private var startedKey: String? = null
    private var seriesTitle = "Anime"
    private var artworkUrl: String? = null
    private var totalEpisodes: Int? = null
    private val syncedAniListEpisodes = mutableSetOf<Int>()
    private var lastRequestedNumber = 1.0
    private var failedProviders = mutableSetOf<String>()
    private val failedStreamUrls = mutableSetOf<String>()
    private var resolveJob: Job? = null
    private var anivexaMergeJob: Job? = null
    private var miruroLateMergeJob: Job? = null
    private var mergedIncludesAnivexa = false
    private var mergedEpisodes = EpisodesResult(emptyList())

    fun start(id: Int, providerName: String, categoryApi: String, episodeNumber: String) {
        val key = "$id/$providerName/$categoryApi/$episodeNumber"
        if (key == startedKey && _state.value is UiState.Success) {
            DiagnosticsLog.event("Watch start ignored duplicate key=$key")
            return
        }
        DiagnosticsLog.event("Watch start key=$key")
        startedKey = key
        anilistId = id
        category = Category.from(categoryApi)
        preferred = providerName
        totalEpisodes = null
        syncedAniListEpisodes.clear()
        failedProviders.clear()
        mergedIncludesAnivexa = false

        resolveJob?.cancel()
        anivexaMergeJob?.cancel()
        miruroLateMergeJob?.cancel()
        resolveJob = viewModelScope.launch {
            _state.value = UiState.Loading
            _loadingStatus.value = null
            try {
                SettingsStore.awaitLoaded()
                val storedPreferred = SettingsStore.preferredProvider.value
                globalPreferredProvider = storedPreferred
                preferred = preferredProviderForWatch(storedPreferred, providerName)
                DiagnosticsLog.event(
                    "Watch preferred server route=$providerName stored=$storedPreferred selected=$preferred",
                )
                DiagnosticsLog.event("Watch episodes load start id=$id")
                // Fast path: both catalogs race from the start. The fast Anivexa subset (plus the
                // preferred server) is already in flight if the Miruro pipe turns out to be slow
                // or down, and the Miruro wait is capped so a dead pipe (Cloudflare timeout)
                // can't hold the loading screen hostage — a late Miruro answer merges in behind.
                // The remaining slow Anivexa scrapers fold in via launchAnivexaMerge.
                val fastCatalog = async {
                    runCatching { repo.fastAnivexaEpisodes(id, setOf(preferred)) }
                        .onFailure { DiagnosticsLog.throwable("Watch fast anivexa episodes failed id=$id", it) }
                        .getOrDefault(EpisodesResult(emptyList()))
                }
                val miruroDeferred = async { runCatching { repo.miruroEpisodes(id) } }
                val miruroResult = withTimeoutOrNull(MIRURO_CATALOG_WAIT_MS) { miruroDeferred.await() }
                miruroResult?.exceptionOrNull()?.let {
                    DiagnosticsLog.throwable("Watch miruro episodes failed id=$id", it)
                }
                if (miruroResult == null) {
                    DiagnosticsLog.event(
                        "Watch miruro episodes still pending after ${MIRURO_CATALOG_WAIT_MS}ms id=$id",
                    )
                }
                val miruro = miruroResult?.getOrNull() ?: EpisodesResult(emptyList())
                if (miruro.isEmpty) {
                    _loadingStatus.value =
                        "The main source looks down right now. Trying other sources…"
                }
                val preferredIsAnivexa =
                    ProviderCatalog.sourceOf(preferred) == ProviderCatalog.Source.ANIVEXA
                val merged = if (!miruro.isEmpty && !preferredIsAnivexa) {
                    // The full Anivexa catalog loads via launchAnivexaMerge anyway; drop the
                    // now-redundant fast subset instead of hitting those providers twice.
                    fastCatalog.cancel()
                    miruro
                } else {
                    val fast = fastCatalog.await()
                    if (fast.isEmpty) {
                        repo.episodes(id).also { mergedIncludesAnivexa = true }
                    } else {
                        DiagnosticsLog.event(
                            "Watch fast catalog id=$id providers=" + fast.providerNames.joinToString(),
                        )
                        repo.mergeProviders(miruro, fast)
                    }
                }
                if (miruroResult == null) launchMiruroLateMerge(id, miruroDeferred)
                DiagnosticsLog.event(
                    "Watch episodes load success id=$id providers=" +
                        merged.providers.joinToString { provider ->
                            "${provider.name}:sub=${provider.sub.size},dub=${provider.dub.size}"
                        },
                )
                mergedEpisodes = merged
                // Prefer dub: launches carrying category=sub (typically history saved before the
                // setting was enabled) upgrade to dub when the catalog actually has the start
                // episode dubbed. In-player category switches are unaffected — this only runs on
                // screen entry — and when no dub exists the launch stays sub instead of erroring.
                if (category == Category.SUB && SettingsStore.preferDub.value) {
                    val startNumber = episodeNumber.toDoubleOrNull()
                    val dubAvailable = merged.providers.any { provider ->
                        provider.dub.isNotEmpty() &&
                            (startNumber == null || provider.dub.any { it.number == startNumber })
                    }
                    if (dubAvailable) {
                        category = Category.DUB
                        DiagnosticsLog.event("Watch category upgraded to dub (prefer dub) id=$id")
                    }
                }
                repo.animeInfo(id)?.let { info ->
                    seriesTitle = info.title.preferred
                    artworkUrl = info.coverImage.best
                    totalEpisodes = info.episodes
                    DiagnosticsLog.event("Watch animeInfo success id=$id title=${seriesTitle.take(80)}")
                }
                spine = pickSpine(merged)
                DiagnosticsLog.event(
                    "Watch spine picked size=${spine.size} preferred=$preferred category=${category.api} " +
                        "first=${spine.firstOrNull()?.displayNumber ?: "none"} last=${spine.lastOrNull()?.displayNumber ?: "none"}",
                )
                if (spine.isEmpty()) error("No episodes for this title")
                val startNumber = episodeNumber.toDoubleOrNull() ?: spine.first().number
                // Launched before the first resolve so a miss on the partial catalog can await
                // the remaining servers instead of reporting "no source" prematurely.
                if (!mergedIncludesAnivexa) launchAnivexaMerge(id)
                resolveAndPlay(startNumber)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch start failed key=$key", e)
                _loadingStatus.value = null
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    /**
     * When the Miruro pipe outlived its initial wait, keep listening: if it eventually answers,
     * fold its providers into the catalog so they become selectable, without disturbing playback
     * that already started on a fast source.
     */
    private fun launchMiruroLateMerge(id: Int, deferred: kotlinx.coroutines.Deferred<Result<EpisodesResult>>) {
        miruroLateMergeJob?.cancel()
        miruroLateMergeJob = viewModelScope.launch {
            val late = runCatching { deferred.await() }.getOrNull()?.getOrNull() ?: return@launch
            if (late.isEmpty) return@launch
            mergedEpisodes = repo.mergeProviders(late, mergedEpisodes)
            spine = pickSpine(mergedEpisodes)
            DiagnosticsLog.event(
                "Watch miruro late merge applied id=$id providers=" + late.providerNames.joinToString(),
            )
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            val number = data.current.number
            val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
            _state.value = UiState.Success(
                data.copy(
                    episodes = spine,
                    currentIndex = index,
                    sourceOptions = sourceOptions(number),
                ),
            )
        }
    }

    /**
     * Fold the slower Anivexa providers in once they arrive, without disturbing playback that
     * already started on a Miruro source. Refreshes the navigation spine and the source list so
     * the extra servers become selectable; the active stream and resolving flag are left as-is.
     */
    private fun launchAnivexaMerge(id: Int) {
        anivexaMergeJob?.cancel()
        anivexaMergeJob = viewModelScope.launch {
            val anivexa = runCatching { repo.anivexaEpisodes(id) }
                .onFailure { DiagnosticsLog.throwable("Watch anivexa merge failed id=$id", it) }
                .getOrNull()
            if (anivexa != null && !anivexa.isEmpty) {
                mergedEpisodes = repo.mergeProviders(mergedEpisodes, anivexa)
                spine = pickSpine(mergedEpisodes)
                DiagnosticsLog.event(
                    "Watch anivexa merge applied id=$id providers=" + mergedEpisodes.providerNames.joinToString(),
                )
            }
            mergedIncludesAnivexa = true
            // Reflect completion whether or not Anivexa added anything: the extra servers become
            // selectable and the "loading more servers" hint clears.
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            val number = data.current.number
            val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
            _state.value = UiState.Success(
                data.copy(
                    episodes = spine,
                    currentIndex = index,
                    sourceOptions = sourceOptions(number),
                    isLoadingMoreSources = false,
                ),
            )
        }
    }

    /**
     * Navigation spine: the longest provider episode list, so Next never dead-ends just because
     * the launched provider's list lags behind the others. Ties keep the chosen provider; source
     * resolution still tries the preferred provider first and falls back per episode.
     */
    private fun pickSpine(merged: EpisodesResult): List<EpisodeItem> {
        return pickNavigationSpine(merged, preferred, category)
    }

    private suspend fun resolveAndPlay(number: Double) {
        failedStreamUrls.clear()
        val requestedProvider = preferred
        DiagnosticsLog.event(
            "Watch resolve start id=$anilistId episode=${fmt(number)} preferred=$requestedProvider " +
                "category=${category.api} excluded=${failedProviders.joinToString()}",
        )
        lastRequestedNumber = number
        // Fetched alongside source resolution: fills intro/outro markers for providers that
        // don't ship their own, so auto-skip keeps working after a provider fallback.
        val aniSkipFallback = viewModelScope.async {
            runCatching { repo.skipTimes(anilistId, number) }.getOrNull()
        }
        val previous = (_state.value as? UiState.Success)?.data
        _state.value = previous?.let { UiState.Success(it.copy(isResolving = true, notice = null)) }
            ?: UiState.Loading
        var resolved = repo.resolveSources(
            anilistId = anilistId,
            number = number,
            preferred = requestedProvider,
            category = category,
            episodes = mergedEpisodes,
            excludedProviders = failedProviders,
        )
        if (resolved == null && !mergedIncludesAnivexa) {
            // The quick partial catalog missed; the slower servers may still carry this episode,
            // so wait for the full merge before declaring no source.
            DiagnosticsLog.event(
                "Watch resolve retry pending full catalog id=$anilistId episode=${fmt(number)}",
            )
            _loadingStatus.value = "Still looking — checking the remaining servers…"
            anivexaMergeJob?.join()
            // Last chance before "no source": the attempt cap only exists to bound mid-playback
            // fallback latency, so here every remaining server gets a try.
            resolved = repo.resolveSources(
                anilistId = anilistId,
                number = number,
                preferred = requestedProvider,
                category = category,
                episodes = mergedEpisodes,
                excludedProviders = failedProviders,
                maxAttempts = Int.MAX_VALUE,
            )
        }
        if (resolved == null) {
            aniSkipFallback.cancel()
            _loadingStatus.value = null
            val message = "No playable source for episode ${fmt(number)} on any server"
            DiagnosticsLog.event("Watch resolve no source id=$anilistId episode=${fmt(number)}")
            _state.value = previous?.let {
                UiState.Success(it.copy(isResolving = false, notice = message))
            } ?: UiState.Error(message)
            return
        }
        val fallbackNotice = if (resolved.provider != requestedProvider) {
            "${ProviderCatalog.label(requestedProvider)} is unavailable for this episode. " +
                "Playing ${ProviderCatalog.label(resolved.provider)} instead."
        } else {
            null
        }
        if (fallbackNotice != null) {
            DiagnosticsLog.event(
                "Watch provider fallback requested=$requestedProvider actual=${resolved.provider} " +
                    "episode=${fmt(number)}",
            )
        }
        val sources = if (resolved.sources.skip == null) {
            val fallbackSkip = withTimeoutOrNull(ANISKIP_WAIT_MS) { aniSkipFallback.await() }
            fallbackSkip?.let { resolved.sources.copy(skip = it) } ?: resolved.sources
        } else {
            aniSkipFallback.cancel()
            resolved.sources
        }
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        val resume = LibraryStore.historyFor(anilistId)?.takeIf { it.episodeNumber == number }?.positionMs ?: 0L
        val chosen = pickProviderStream(resolved.provider, sources)
        DiagnosticsLog.event(
            "Watch resolve success provider=${resolved.provider} episode=${fmt(number)} index=$index " +
                "hls=${resolved.sources.hlsStreams.size} total=${resolved.sources.streams.size} " +
                "embed=${resolved.sources.embedStreams.size} subtitles=${resolved.sources.subtitles.size} " +
                "chosen=${chosen?.diagnosticLabel() ?: "none"} resumeMs=$resume",
        )
        DiagnosticsLog.event(
            "Watch source inventory provider=${resolved.provider} " +
                resolved.sources.streams.joinToString(separator = ",", limit = 16, truncated = "...") {
                    "${it.diagnosticLabel()}${if (it.isActive) "*" else ""}"
                },
        )
        _loadingStatus.value = null
        _state.value = UiState.Success(
            WatchData(
                episodes = spine,
                currentIndex = index,
                provider = resolved.provider,
                category = category,
                sourceOptions = sourceOptions(number),
                anilistId = anilistId,
                sources = sources,
                chosenStream = chosen,
                seriesTitle = seriesTitle,
                artworkUrl = artworkUrl,
                startPositionMs = resume,
                preferredProvider = globalPreferredProvider,
                isResolving = false,
                isLoadingMoreSources = !mergedIncludesAnivexa,
                notice = fallbackNotice,
            ),
        )
        recordHistory(number, resolved.provider)
    }

    fun changeSource(providerName: String, categoryApi: String) {
        DiagnosticsLog.event("Watch changeSource requested provider=$providerName category=$categoryApi")
        switchSource(providerName, categoryApi, rememberProvider = true)
    }

    fun changeCategory(categoryApi: String) {
        val providerName = (_state.value as? UiState.Success)?.data?.provider ?: return
        DiagnosticsLog.event("Watch changeCategory requested provider=$providerName category=$categoryApi")
        switchSource(providerName, categoryApi, rememberProvider = false)
    }

    private fun switchSource(providerName: String, categoryApi: String, rememberProvider: Boolean) {
        val nextCategory = Category.from(categoryApi)
        val provider = mergedEpisodes.provider(providerName) ?: return
        val nextSpine = provider.episodes(nextCategory).takeIf { it.isNotEmpty() } ?: return
        val current = (_state.value as? UiState.Success)?.data
        val currentNumber = current?.current?.number ?: lastRequestedNumber

        if (rememberProvider) {
            preferred = providerName
            globalPreferredProvider = providerName
            SettingsStore.setPreferredProvider(providerName)
        }
        if (current?.provider == providerName && current.category == nextCategory) {
            if (rememberProvider) {
                _state.value = UiState.Success(
                    current.copy(
                        preferredProvider = providerName,
                        notice = "${ProviderCatalog.label(providerName)} is now your preferred server.",
                    ),
                )
            }
            return
        }
        category = nextCategory
        spine = nextSpine
        failedProviders.clear()
        if (current != null) {
            _state.value = UiState.Success(
                current.copy(
                    preferredProvider = globalPreferredProvider,
                    notice = if (rememberProvider) {
                        "${ProviderCatalog.label(providerName)} is now your preferred server."
                    } else {
                        current.notice
                    },
                ),
            )
        }
        launchResolve(nextSpine.firstOrNull { it.number == currentNumber }?.number ?: nextSpine.first().number)
    }

    private suspend fun recordHistory(number: Double, provider: String) {
        val ep = spine.firstOrNull { it.number == number }
        LibraryStore.upsertHistory(
            HistoryEntry(
                anilistId = anilistId,
                title = seriesTitle,
                cover = artworkUrl,
                episodeNumber = number,
                episodeTitle = ep?.title,
                provider = provider,
                category = category.api,
                positionMs = LibraryStore.historyFor(anilistId)?.takeIf { it.episodeNumber == number }?.positionMs ?: 0L,
                durationMs = LibraryStore.historyFor(anilistId)?.takeIf { it.episodeNumber == number }?.durationMs ?: 0L,
            ),
        )
    }

    private var lastProgressSave = 0L
    fun onProgress(positionMs: Long, durationMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        maybeSyncAniListProgress(data.current.number, positionMs, durationMs)
        val now = System.currentTimeMillis()
        if (now - lastProgressSave < 8_000) return
        lastProgressSave = now
        LibraryStore.updateProgress(anilistId, data.current.number, positionMs, durationMs)
    }

    private fun maybeSyncAniListProgress(episodeNumber: Double, positionMs: Long, durationMs: Long) {
        val service = AccountService.active ?: return
        if (!SettingsStore.autoSyncAniList.value) return
        if (!shouldSyncAniListProgress(episodeNumber, positionMs, durationMs)) return
        val episode = episodeNumber.toInt()
        if (!syncedAniListEpisodes.add(episode)) return
        viewModelScope.launch {
            runCatching {
                when (service) {
                    AccountService.ANILIST -> repo.saveAniListProgress(anilistId, episode, totalEpisodes)
                    AccountService.MAL -> repo.saveMalProgress(anilistId, episode, totalEpisodes)
                }
            }
                .onFailure {
                    syncedAniListEpisodes.remove(episode)
                    DiagnosticsLog.throwable(
                        "Watch ${service.label} progress sync failed id=$anilistId episode=${fmt(episodeNumber)}",
                        it,
                    )
                }
        }
    }

    fun playIndex(index: Int) {
        DiagnosticsLog.event("Watch playIndex requested index=$index spineSize=${spine.size}")
        if (index !in spine.indices) {
            DiagnosticsLog.event("Watch playIndex ignored out of bounds index=$index")
            return
        }
        failedProviders.clear()
        launchResolve(spine[index].number)
    }

    fun next() {
        DiagnosticsLog.event("Watch next requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur + 1)
    }

    fun prev() {
        DiagnosticsLog.event("Watch prev requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur - 1)
    }

    fun retry() {
        DiagnosticsLog.event("Watch retry requested episode=${fmt(lastRequestedNumber)}")
        failedProviders.clear()
        launchResolve(lastRequestedNumber)
    }

    /** All episode resolution goes through here so a failure becomes an error state, not a crash. */
    private fun launchResolve(number: Double, before: (suspend () -> Unit)? = null) {
        DiagnosticsLog.event("Watch launchResolve episode=${fmt(number)}")
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                before?.invoke()
                resolveAndPlay(number)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch resolve failed id=$anilistId episode=${fmt(number)}", e)
                _loadingStatus.value = null
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    fun onPlaybackError(message: String, streamUrl: String, positionMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        DiagnosticsLog.event(
            "Watch playback error provider=${data.provider} episode=${data.current.displayNumber} " +
                "streamHost=${runCatching { Uri.parse(streamUrl).host }.getOrNull() ?: "unknown"} " +
                "positionMs=$positionMs message=${message.take(160)}",
        )

        if (data.provider == "allanime" && streamUrl.isNotBlank()) {
            if (!failedStreamUrls.add(streamUrl)) {
                DiagnosticsLog.event("Watch ignored duplicate AllAnime stream failure host=${Uri.parse(streamUrl).host}")
                return
            }
            val next = nextProviderStream(
                provider = data.provider,
                sources = data.sources,
                currentUrl = streamUrl,
                failedUrls = failedStreamUrls,
            )
            if (next != null) {
                val failed = data.sources.streams.firstOrNull { it.url == streamUrl }
                val resume = maxOf(data.startPositionMs, positionMs.coerceAtLeast(0L))
                DiagnosticsLog.event(
                    "Watch AllAnime internal fallback failed=${failed?.diagnosticLabel() ?: "unknown"} " +
                        "next=${next.diagnosticLabel()} attempted=${failedStreamUrls.size} resumeMs=$resume",
                )
                _state.value = UiState.Success(
                    data.copy(
                        chosenStream = next,
                        startPositionMs = resume,
                        playbackGeneration = data.playbackGeneration + 1,
                        notice = "${failed?.label ?: "AllAnime source"} failed. Trying ${next.label}…",
                    ),
                )
                return
            }
            DiagnosticsLog.event(
                "Watch AllAnime streams exhausted attempted=${failedStreamUrls.size}; trying another provider",
            )
        }

        failedProviders += data.provider
        launchResolve(data.current.number) {
            _state.value = UiState.Success(
                data.copy(
                    isResolving = true,
                    notice = "${ProviderCatalog.label(data.provider)} failed: $message. Trying another source…",
                ),
            )
        }
    }

    private fun sourceOptions(number: Double): List<WatchSourceOption> =
        mergedEpisodes.providers
            .flatMap { provider ->
                provider.categories.map { category ->
                    val episodes = provider.episodes(category)
                    WatchSourceOption(
                        provider = provider.name,
                        category = category,
                        hasCurrentEpisode = episodes.any { it.number == number },
                        episodeCount = episodes.size,
                    )
                }
            }
            .filter { it.episodeCount > 0 }
            .sortedWith(compareBy<WatchSourceOption> { ProviderCatalog.sortKey(it.provider) }.thenBy { it.category.ordinal })

    private fun fmt(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private fun StreamItem.diagnosticLabel(): String {
        val type = when {
            isEmbed -> "embed"
            isHls -> "hls"
            else -> "direct"
        }
        return "$type label=${label.take(48)} audio=${audio ?: "unknown"} " +
            "height=${height ?: "auto"} host=${runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"}"
    }
}

internal fun shouldSyncAniListProgress(episodeNumber: Double, positionMs: Long, durationMs: Long): Boolean {
    if (episodeNumber < 1 || episodeNumber % 1.0 != 0.0) return false
    if (durationMs < MIN_ANILIST_SYNC_DURATION_MS || positionMs <= 0) return false
    return positionMs.toDouble() / durationMs >= ANILIST_SYNC_WATCHED_FRACTION
}

private const val MIN_ANILIST_SYNC_DURATION_MS = 60_000L
private const val ANILIST_SYNC_WATCHED_FRACTION = 0.80

/** Provider-specific first-player policy, applied only after that provider has resolved sources. */
internal fun pickProviderStream(provider: String, sources: SourcesResult): StreamItem? {
    val direct = sources.streams.filterNot(StreamItem::isEmbed)
    val embeds = sources.embedStreams

    return when (provider) {
        // Kwik's fixed-quality CDN URLs currently return 403 outside its page. The embed carries
        // the cookies/player flow those URLs require, so do not leave Media3 buffering forever.
        "kiwi" -> embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: bestHls(direct)
            ?: direct.firstOrNull()

        // Ally mixes AllAnime progressive files with an unreliable HLS mirror. Its direct files
        // are independently playable and retain the selected SUB/DUB category.
        "ally" -> direct.firstOrNull { !it.isHls }
            ?: bestHls(direct)
            ?: embeds.firstOrNull()

        else -> bestHls(direct)
            ?: direct.firstOrNull()
            ?: embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: sources.streams.firstOrNull()
    }
}

/** Every distinct stream in the same order the provider's first-player policy would choose it. */
internal fun providerStreamOrder(
    provider: String,
    sources: SourcesResult,
): List<StreamItem> {
    val remaining = sources.streams.distinctBy(StreamItem::url).toMutableList()
    return buildList {
        while (remaining.isNotEmpty()) {
            val next = pickProviderStream(provider, sources.copy(streams = remaining)) ?: break
            add(next)
            remaining.removeAll { it.url == next.url }
        }
    }
}

/** Finds the next untried stream after the one that failed, wrapping once before giving up. */
internal fun nextProviderStream(
    provider: String,
    sources: SourcesResult,
    currentUrl: String,
    failedUrls: Set<String>,
): StreamItem? {
    val ordered = providerStreamOrder(provider, sources)
    if (ordered.isEmpty()) return null
    val currentIndex = ordered.indexOfFirst { it.url == currentUrl }
    val candidates = if (currentIndex >= 0) {
        ordered.drop(currentIndex + 1) + ordered.take(currentIndex)
    } else {
        ordered
    }
    return candidates.firstOrNull { it.url !in failedUrls }
}

/** A saved global choice wins; before the first explicit choice, keep the launch route behavior. */
internal fun preferredProviderForWatch(storedPreferred: String?, routeProvider: String): String {
    val stored = storedPreferred?.trim()?.lowercase().orEmpty()
    if (stored.isNotBlank() && stored != DEFAULT_PREFERRED_PROVIDER) return stored
    return routeProvider.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
}

private fun bestHls(streams: List<StreamItem>): StreamItem? = streams
    .filter(StreamItem::isHls)
    .maxByOrNull { (it.height ?: 0) + if (it.isActive) 100_000 else 0 }

internal fun pickNavigationSpine(
    episodes: EpisodesResult,
    preferred: String,
    category: Category,
): List<EpisodeItem> {
    fun normalized(provider: String): List<EpisodeItem> = episodes.provider(provider)
        ?.episodes(category)
        .orEmpty()
        .distinctBy(EpisodeItem::number)
        .sortedBy(EpisodeItem::number)

    val preferredList = normalized(preferred)
    val longest = episodes.providerNames
        .asSequence()
        .map(::normalized)
        .maxByOrNull(List<EpisodeItem>::size)
        .orEmpty()
    return if (preferredList.size >= longest.size) preferredList else longest
}
