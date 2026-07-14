package com.miruronative.ui.watch

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.settings.SettingsStore
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
    val isResolving: Boolean = false,
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

    private var anilistId = 0
    private var category = Category.SUB
    private var preferred = ""
    private var spine: List<EpisodeItem> = emptyList()
    private var startedKey: String? = null
    private var seriesTitle = "Anime"
    private var artworkUrl: String? = null
    private var lastRequestedNumber = 1.0
    private var failedProviders = mutableSetOf<String>()
    private var resolveJob: Job? = null
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

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                DiagnosticsLog.event("Watch episodes load start id=$id")
                val merged = repo.episodes(id)
                DiagnosticsLog.event(
                    "Watch episodes load success id=$id providers=" +
                        merged.providers.joinToString { provider ->
                            "${provider.name}:sub=${provider.sub.size},dub=${provider.dub.size}"
                        },
                )
                mergedEpisodes = merged
                repo.animeInfo(id)?.let { info ->
                    seriesTitle = info.title.preferred
                    artworkUrl = info.coverImage.best
                    DiagnosticsLog.event("Watch animeInfo success id=$id title=${seriesTitle.take(80)}")
                }
                spine = pickSpine(merged)
                DiagnosticsLog.event(
                    "Watch spine picked size=${spine.size} preferred=$preferred category=${category.api} " +
                        "first=${spine.firstOrNull()?.displayNumber ?: "none"} last=${spine.lastOrNull()?.displayNumber ?: "none"}",
                )
                if (spine.isEmpty()) error("No episodes for this title")
                val startNumber = episodeNumber.toDoubleOrNull() ?: spine.first().number
                resolveAndPlay(startNumber)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch start failed key=$key", e)
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    /**
     * Navigation spine: the longest provider episode list, so Next never dead-ends just because
     * the launched provider's list lags behind the others. Ties keep the chosen provider; source
     * resolution still tries the preferred provider first and falls back per episode.
     */
    private fun pickSpine(merged: EpisodesResult): List<EpisodeItem> {
        val preferredList = merged.provider(preferred)?.episodes(category).orEmpty()
        val longest = merged.providers.map { it.episodes(category) }.maxByOrNull { it.size }.orEmpty()
        return if (preferredList.size >= longest.size) preferredList else longest
    }

    private suspend fun resolveAndPlay(number: Double) {
        DiagnosticsLog.event(
            "Watch resolve start id=$anilistId episode=${fmt(number)} preferred=$preferred " +
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
        val resolved = repo.resolveSources(
            anilistId = anilistId,
            number = number,
            preferred = preferred,
            category = category,
            excludedProviders = failedProviders,
        )
        if (resolved == null) {
            aniSkipFallback.cancel()
            val message = "No playable source for episode ${fmt(number)} on any server"
            DiagnosticsLog.event("Watch resolve no source id=$anilistId episode=${fmt(number)}")
            _state.value = previous?.let {
                UiState.Success(it.copy(isResolving = false, notice = message))
            } ?: UiState.Error(message)
            return
        }
        preferred = resolved.provider // stick with whatever actually served the stream
        val sources = if (resolved.sources.skip == null) {
            val fallbackSkip = withTimeoutOrNull(ANISKIP_WAIT_MS) { aniSkipFallback.await() }
            fallbackSkip?.let { resolved.sources.copy(skip = it) } ?: resolved.sources
        } else {
            aniSkipFallback.cancel()
            resolved.sources
        }
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        val resume = LibraryStore.historyFor(anilistId)?.takeIf { it.episodeNumber == number }?.positionMs ?: 0L
        val chosen = pickStream(resolved.sources)
        DiagnosticsLog.event(
            "Watch resolve success provider=${resolved.provider} episode=${fmt(number)} index=$index " +
                "hls=${resolved.sources.hlsStreams.size} direct=${resolved.sources.streams.size} " +
                "embed=${resolved.sources.embedStreams.size} subtitles=${resolved.sources.subtitles.size} " +
                "chosen=${chosen?.diagnosticLabel() ?: "none"} resumeMs=$resume",
        )
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
                isResolving = false,
            ),
        )
        recordHistory(number, resolved.provider)
    }

    fun changeSource(providerName: String, categoryApi: String) {
        DiagnosticsLog.event("Watch changeSource requested provider=$providerName category=$categoryApi")
        val nextCategory = Category.from(categoryApi)
        val provider = mergedEpisodes.provider(providerName) ?: return
        val nextSpine = provider.episodes(nextCategory).takeIf { it.isNotEmpty() } ?: return
        val currentNumber = (_state.value as? UiState.Success)?.data?.current?.number ?: lastRequestedNumber

        preferred = providerName
        category = nextCategory
        spine = nextSpine
        failedProviders.clear()
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
        if (AuthManager.isLoggedIn && SettingsStore.autoSyncAniList.value) {
            runCatching { repo.saveAniListProgress(anilistId, number.toInt()) }
                .onFailure { DiagnosticsLog.throwable("Watch AniList progress sync failed id=$anilistId episode=${fmt(number)}", it) }
        }
    }

    private var lastProgressSave = 0L
    fun onProgress(positionMs: Long, durationMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val now = System.currentTimeMillis()
        if (now - lastProgressSave < 8_000) return
        lastProgressSave = now
        LibraryStore.updateProgress(anilistId, data.current.number, positionMs, durationMs)
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
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    fun onPlaybackError(message: String) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        DiagnosticsLog.event(
            "Watch playback error provider=${data.provider} episode=${data.current.displayNumber} " +
                "message=${message.take(160)}",
        )
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

    private fun pickStream(sources: SourcesResult): StreamItem? =
        sources.hlsStreams.maxByOrNull { (it.height ?: 0) + if (it.isActive) 100_000 else 0 }
            // Any other direct stream (progressive MP4) still beats a WebView embed.
            ?: sources.streams.firstOrNull { !it.isEmbed }
            ?: sources.embedStreams.firstOrNull()
            ?: sources.streams.firstOrNull()

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
        return "$type height=${height ?: "auto"} host=${runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"}"
    }
}
