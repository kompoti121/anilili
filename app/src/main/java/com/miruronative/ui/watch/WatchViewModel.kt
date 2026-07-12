package com.miruronative.ui.watch

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
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class WatchData(
    val episodes: List<EpisodeItem>,
    val currentIndex: Int,
    val provider: String,
    val category: Category,
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

    fun start(id: Int, providerName: String, categoryApi: String, episodeNumber: String) {
        val key = "$id/$providerName/$categoryApi/$episodeNumber"
        if (key == startedKey && _state.value is UiState.Success) return
        startedKey = key
        anilistId = id
        category = Category.from(categoryApi)
        preferred = providerName

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val merged = repo.episodes(id)
                repo.animeInfo(id)?.let { info ->
                    seriesTitle = info.title.preferred
                    artworkUrl = info.coverImage.best
                }
                spine = pickSpine(merged)
                if (spine.isEmpty()) error("No episodes for this title")
                val startNumber = episodeNumber.toDoubleOrNull() ?: spine.first().number
                resolveAndPlay(startNumber)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    /** Navigation spine: the chosen provider's episode list, else whichever has the most episodes. */
    private fun pickSpine(merged: EpisodesResult): List<EpisodeItem> {
        merged.provider(preferred)?.episodes(category)?.takeIf { it.isNotEmpty() }?.let { return it }
        return merged.providers.map { it.episodes(category) }.maxByOrNull { it.size } ?: emptyList()
    }

    private suspend fun resolveAndPlay(number: Double) {
        lastRequestedNumber = number
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
            val message = "No playable source for episode ${fmt(number)} on any server"
            _state.value = previous?.let {
                UiState.Success(it.copy(isResolving = false, notice = message))
            } ?: UiState.Error(message)
            return
        }
        preferred = resolved.provider // stick with whatever actually served the stream
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        val resume = LibraryStore.historyFor(anilistId)?.takeIf { it.episodeNumber == number }?.positionMs ?: 0L
        _state.value = UiState.Success(
            WatchData(
                episodes = spine,
                currentIndex = index,
                provider = resolved.provider,
                category = category,
                anilistId = anilistId,
                sources = resolved.sources,
                chosenStream = pickStream(resolved.sources),
                seriesTitle = seriesTitle,
                artworkUrl = artworkUrl,
                startPositionMs = resume,
                isResolving = false,
            ),
        )
        recordHistory(number, resolved.provider)
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
        if (index !in spine.indices) return
        failedProviders.clear()
        launchResolve(spine[index].number)
    }

    fun next() {
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur + 1)
    }

    fun prev() {
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur - 1)
    }

    fun retry() {
        failedProviders.clear()
        launchResolve(lastRequestedNumber)
    }

    /** All episode resolution goes through here so a failure becomes an error state, not a crash. */
    private fun launchResolve(number: Double, before: (suspend () -> Unit)? = null) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                before?.invoke()
                resolveAndPlay(number)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    fun onPlaybackError(message: String) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
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
            ?: sources.embedStreams.firstOrNull()
            ?: sources.streams.firstOrNull()

    private fun fmt(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
}
