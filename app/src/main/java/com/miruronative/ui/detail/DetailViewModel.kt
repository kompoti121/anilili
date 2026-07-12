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
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailData(
    val info: Media,
    val episodes: EpisodesResult,
    val episodesError: String?,
    val loadingMore: Boolean = false,
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
                // Start independent calls together. Miruro still renders first, while the
                // slower Anivexa batch no longer waits behind metadata and pipe discovery.
                val infoRequest = async { runCatching { repo.animeInfo(id, force = force) } }
                val miruroRequest = async { runCatching { repo.miruroEpisodes(id, force = force) } }
                val anivexaRequest = async { runCatching { repo.anivexaEpisodes(id, force = force) } }

                val info = infoRequest.await().getOrThrow() ?: error("Anime not found")

                // 1) Miruro pipe first (fast).
                val miruroResult = miruroRequest.await()
                val miruro = miruroResult.getOrDefault(EpisodesResult(emptyList()))
                applyDefaults(miruro)
                val firstError = if (miruro.providers.isEmpty() && miruroResult.isFailure) {
                    "Some servers unavailable"
                } else null
                _state.value = UiState.Success(DetailData(info, miruro, firstError, loadingMore = true))

                // 2) Merge the Anivexa batch, which has already been loading in parallel.
                val anivexa = anivexaRequest.await().getOrDefault(EpisodesResult(emptyList()))
                val merged = repo.mergeProviders(miruro, anivexa)
                if (selectedProvider == null) applyDefaults(merged)
                val err = if (merged.providers.isEmpty()) "No streaming sources found" else null
                _state.value = UiState.Success(DetailData(info, merged, err, loadingMore = false))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Failed to load")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh(id: Int) = load(id, force = true)

    private fun applyDefaults(eps: EpisodesResult) {
        val current = selectedProvider
        if (current == null || eps.provider(current) == null) {
            selectedProvider = eps.providers.firstOrNull()?.name
        }
        selectedCategory = selectedProvider
            ?.let { eps.provider(it)?.categories?.firstOrNull() }
            ?: Category.SUB
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
