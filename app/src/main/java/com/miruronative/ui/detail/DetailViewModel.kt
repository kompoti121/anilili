package com.miruronative.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.Media
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the anime page waits on the slow Anivexa scraper batch before rendering what it has.
 * Their own per-provider timeout is 15s, which is far too long to leave the page blank.
 */
private const val ANIVEXA_WAIT_MS = 6_000L

data class DetailData(
    val info: Media,
    val episodes: EpisodesResult,
    val episodesError: String?,
    val loadingMore: Boolean = false,
    val series: List<Media> = listOf(info),
    val seriesLoading: Boolean = true,
)

class DetailViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<DetailData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    var selectedProvider by mutableStateOf<String?>(null)
        private set
    var selectedCategory by mutableStateOf(Category.SUB)
        private set

    private var loadedId: Int? = null

    fun load(id: Int, force: Boolean = false) {
        if (!force && loadedId == id && _state.value is UiState.Success) return
        loadedId = id
        selectedProvider = null
        viewModelScope.launch {
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                // The prefer-dub setting seeds the default category below; make sure the
                // persisted value has loaded before the first applyDefaults call.
                SettingsStore.awaitLoaded()
                // Start independent calls together. Miruro still renders first, while the
                // slower Anivexa batch no longer waits behind metadata and pipe discovery.
                val infoRequest = async { runCatching { repo.animeInfo(id, force = force) } }
                val miruroRequest = async { runCatching { repo.miruroEpisodes(id, force = force) } }
                val anivexaRequest = async { runCatching { repo.anivexaEpisodes(id, force = force) } }

                val info = infoRequest.await().getOrThrow() ?: error("Anime not found")
                val seriesRequest = async { runCatching { repo.animeSeries(info) } }

                // 1) Miruro pipe first (fast).
                val miruroResult = miruroRequest.await()
                val miruro = miruroResult.getOrDefault(EpisodesResult(emptyList()))
                applyDefaults(miruro)
                val firstError = if (miruro.providers.isEmpty() && miruroResult.isFailure) {
                    "Some servers unavailable"
                } else null
                _state.value = UiState.Success(
                    DetailData(
                        info = info,
                        episodes = miruro,
                        episodesError = firstError,
                        loadingMore = true,
                    ),
                )

                // 2) Merge the Anivexa batch, which has already been loading in parallel. The wait
                // is bounded: a provider that never answers must not leave the page sitting with
                // no episodes, no message, and no spinner. Late results still fold in below.
                val anivexa = withTimeoutOrNull(ANIVEXA_WAIT_MS) { anivexaRequest.await() }
                    ?.getOrDefault(EpisodesResult(emptyList()))
                publishEpisodes(
                    id = id,
                    info = info,
                    miruro = miruro,
                    anivexa = anivexa ?: EpisodesResult(emptyList()),
                    stillLoading = anivexa == null,
                )
                if (anivexa == null) {
                    // Still running: keep the page usable now and update when it lands.
                    launch {
                        val late = runCatching { anivexaRequest.await() }.getOrNull()
                            ?.getOrDefault(EpisodesResult(emptyList()))
                            ?: EpisodesResult(emptyList())
                        if (loadedId == id) {
                            publishEpisodes(id, info, miruro, late, stillLoading = false)
                        }
                    }
                }

                val series = seriesRequest.await().getOrDefault(listOf(info))
                val current = (_state.value as? UiState.Success)?.data
                if (loadedId == id && current != null) {
                    _state.value = UiState.Success(current.copy(series = series, seriesLoading = false))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Failed to load")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Publishes the merged catalog, preserving whatever series data the state already carries. */
    private fun publishEpisodes(
        id: Int,
        info: Media,
        miruro: EpisodesResult,
        anivexa: EpisodesResult,
        stillLoading: Boolean,
    ) {
        if (loadedId != id) return
        val merged = repo.mergeProviders(miruro, anivexa)
        DiagnosticsLog.event(
            "Detail episodes id=$id miruro=${miruro.providerNames.size} anivexa=${anivexa.providerNames.size} " +
                "merged=${merged.providerNames.joinToString().ifEmpty { "none" }} stillLoading=$stillLoading",
        )
        if (selectedProvider == null) applyDefaults(merged)
        val current = (_state.value as? UiState.Success)?.data
        _state.value = UiState.Success(
            DetailData(
                info = info,
                episodes = merged,
                episodesError = when {
                    merged.providers.isNotEmpty() -> null
                    stillLoading -> null
                    else -> "No streaming sources found for this title yet."
                },
                loadingMore = stillLoading,
                series = current?.series ?: listOf(info),
                seriesLoading = current?.seriesLoading ?: true,
            ),
        )
    }

    fun refresh(id: Int) = load(id, force = true)

    private fun applyDefaults(eps: EpisodesResult) {
        val current = selectedProvider
        if (current == null || eps.provider(current) == null) {
            selectedProvider = eps.providers.firstOrNull()?.name
        }
        val categories = selectedProvider?.let { eps.provider(it)?.categories }.orEmpty()
        selectedCategory = when {
            SettingsStore.preferDub.value && Category.DUB in categories -> Category.DUB
            else -> categories.firstOrNull() ?: Category.SUB
        }
    }

    fun selectProvider(provider: String) {
        selectedProvider = provider
        val data = (_state.value as? UiState.Success)?.data ?: return
        val categories = data.episodes.provider(provider)?.categories.orEmpty()
        if (selectedCategory !in categories) {
            selectedCategory = categories.firstOrNull() ?: Category.SUB
        }
    }

    fun selectCategory(category: Category) {
        selectedCategory = category
    }
}
