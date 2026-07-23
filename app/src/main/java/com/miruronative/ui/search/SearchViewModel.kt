package com.miruronative.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.library.SearchHistoryStore
import com.miruronative.data.model.DiscoverFilters
import com.miruronative.data.model.DiscoverOptions
import com.miruronative.data.model.Media
import com.miruronative.data.model.StudioNode
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CatalogChoice(val value: String, val label: String)

class SearchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    var filters by mutableStateOf(DiscoverFilters())
        private set

    val query: String get() = filters.query

    /** Past search terms, most recent first; surfaced when the field is empty. */
    val searchHistory = SearchHistoryStore.history

    /** Applies a term from the recent-searches list as if the user had typed it. */
    fun applyHistoryQuery(term: String) {
        SearchHistoryStore.record(term)
        update(filters.copy(query = term), delayMs = 0)
    }

    fun removeHistoryQuery(term: String) = SearchHistoryStore.remove(term)
    fun clearHistory() = SearchHistoryStore.clear()

    /**
     * Persist the current query as a real search. Called when the user commits — presses the
     * keyboard's search key, or opens a result — never on every keystroke, so partial terms
     * ("fr", "fri") never pollute the list.
     */
    fun recordCurrentSearch() = SearchHistoryStore.record(filters.query)

    private val _state = MutableStateFlow<UiState<List<Media>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    private val _options = MutableStateFlow(DiscoverOptions(genres = DEFAULT_GENRES))
    val options = _options.asStateFlow()

    var studioQuery by mutableStateOf("")
        private set
    private val _studioSuggestions = MutableStateFlow<List<StudioNode>>(emptyList())
    val studioSuggestions = _studioSuggestions.asStateFlow()
    private val _isStudioLookupLoading = MutableStateFlow(false)
    val isStudioLookupLoading = _isStudioLookupLoading.asStateFlow()

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var studioLookupJob: Job? = null
    private var currentPage = 1
    private var hasNextPage = false
    private var requestGeneration = 0

    init {
        viewModelScope.launch {
            runCatching { repo.discoverOptions() }
                .onSuccess { loaded ->
                    _options.value = loaded.copy(
                        genres = (DEFAULT_GENRES + loaded.genres).distinct(),
                    )
                }
        }
        submit(delayMs = 0)
    }

    fun onQueryChange(value: String) = update(filters.copy(query = value), 350)

    fun toggleGenre(value: String) = update(
        filters.copy(genres = filters.genres.toggle(value)),
    )

    fun toggleTag(value: String) = update(
        filters.copy(tags = filters.tags.toggle(value)),
    )

    fun setYear(value: Int?) = update(filters.copy(year = value))
    fun setStatus(value: String?) = update(filters.copy(status = value))
    fun setFormat(value: String?) = update(filters.copy(format = value))
    fun setMinimumScore(value: Int?) = update(filters.copy(minimumScore = value))
    fun setSort(value: String) = update(filters.copy(sort = value))

    fun onStudioQueryChange(value: String) {
        studioQuery = value
        if (filters.studioId != null && value.trim() != filters.studioName) {
            update(filters.copy(studioId = null, studioName = null))
        }
        studioLookupJob?.cancel()
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        val term = value.trim()
        if (term.length < 2 || term == filters.studioName) return
        studioLookupJob = viewModelScope.launch {
            delay(250)
            _isStudioLookupLoading.value = true
            val matches = runCatching { repo.searchStudios(term) }.getOrElse {
                it.rethrowIfCancellation()
                emptyList()
            }
            if (studioQuery.trim() == term) _studioSuggestions.value = matches
            _isStudioLookupLoading.value = false
        }
    }

    fun selectStudio(studio: StudioNode) {
        val name = studio.name?.trim().orEmpty()
        if (studio.id <= 0 || name.isEmpty()) return
        studioLookupJob?.cancel()
        studioQuery = name
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(filters.copy(studioId = studio.id, studioName = name), delayMs = 0)
    }

    fun selectFirstStudioSuggestion() {
        _studioSuggestions.value.firstOrNull()?.let(::selectStudio)
    }

    fun clearStudio() {
        studioLookupJob?.cancel()
        studioQuery = ""
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(filters.copy(studioId = null, studioName = null), delayMs = 0)
    }

    /** Applies an exact studio received from a detail-page navigation route. */
    fun applyStudioFilter(studioId: Int, studioName: String) {
        val name = studioName.trim()
        if (studioId <= 0 || name.isEmpty()) return
        if (filters.studioId == studioId && filters.studioName == name) {
            studioQuery = name
            return
        }
        selectStudio(StudioNode(id = studioId, name = name, isAnimationStudio = true))
    }

    fun clearFilters() {
        studioLookupJob?.cancel()
        studioQuery = ""
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(DiscoverFilters(query = filters.query))
    }

    fun clearAll() {
        studioLookupJob?.cancel()
        studioQuery = ""
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(DiscoverFilters())
    }
    fun retry() = submit(delayMs = 0)
    fun refresh() = submit(delayMs = 0, force = true)

    private fun update(updated: DiscoverFilters, delayMs: Long = 120) {
        filters = updated
        submit(delayMs)
    }

    private fun submit(delayMs: Long, force: Boolean = false) {
        requestGeneration++
        val generation = requestGeneration
        searchJob?.cancel()
        loadMoreJob?.cancel()
        _isLoadingMore.value = false
        searchJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            val requestedFilters = filters
            runCatching { repo.discover(requestedFilters, page = 1, force = force) }
                .onSuccess { page ->
                    if (generation != requestGeneration) return@onSuccess
                    currentPage = page.page
                    hasNextPage = page.hasNextPage
                    _state.value = UiState.Success(page.items)
                }
                .onFailure {
                    it.rethrowIfCancellation()
                    if (generation != requestGeneration) return@onFailure
                    _state.value = UiState.Error(it.message ?: "Could not load the catalog")
                }
            if (generation == requestGeneration) _isRefreshing.value = false
        }
    }

    fun loadMore() {
        if (!hasNextPage || _isLoadingMore.value || loadMoreJob?.isActive == true) return
        val existing = (_state.value as? UiState.Success)?.data ?: return
        val requestedFilters = filters
        val generation = requestGeneration
        val nextPage = currentPage + 1
        _isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch {
            runCatching { repo.discover(requestedFilters, page = nextPage) }
                .onSuccess { page ->
                    if (generation != requestGeneration || requestedFilters != filters) return@onSuccess
                    currentPage = page.page
                    hasNextPage = page.hasNextPage
                    _state.value = UiState.Success((existing + page.items).distinctBy(Media::id))
                }
                .onFailure { it.rethrowIfCancellation() }
            if (generation == requestGeneration) _isLoadingMore.value = false
        }
    }

    companion object {
        val DEFAULT_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance",
            "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Mystery", "Thriller",
        )

        val SORTS = listOf(
            CatalogChoice("TRENDING_DESC", "Trending"),
            CatalogChoice("POPULARITY_DESC", "Most popular"),
            CatalogChoice("SCORE_DESC", "Highest rated"),
            CatalogChoice("START_DATE_DESC", "Newest"),
            CatalogChoice("START_DATE", "Oldest"),
            CatalogChoice("TITLE_ROMAJI", "A–Z"),
        )

        val STATUSES = listOf(
            CatalogChoice("RELEASING", "Ongoing"),
            CatalogChoice("FINISHED", "Completed"),
            CatalogChoice("NOT_YET_RELEASED", "Upcoming"),
            CatalogChoice("HIATUS", "On hiatus"),
            CatalogChoice("CANCELLED", "Cancelled"),
        )

        val FORMATS = listOf(
            CatalogChoice("TV", "TV"),
            CatalogChoice("MOVIE", "Movie"),
            CatalogChoice("TV_SHORT", "TV short"),
            CatalogChoice("OVA", "OVA"),
            CatalogChoice("ONA", "ONA"),
            CatalogChoice("SPECIAL", "Special"),
            CatalogChoice("MUSIC", "Music"),
        )

        val RATINGS = listOf(60, 70, 80, 90)
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value
