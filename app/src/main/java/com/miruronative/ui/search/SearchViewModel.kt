package com.miruronative.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    var query by mutableStateOf("")
        private set

    private val _state = MutableStateFlow<UiState<List<Media>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private var discovery: List<Media> = emptyList()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { repo.trending().items.take(18) }
                .onSuccess { discovery = it; _state.value = UiState.Success(it) }
                .onFailure { _state.value = UiState.Error(it.message ?: "Failed to load suggestions") }
        }
    }

    fun onQueryChange(newQuery: String) {
        query = newQuery
        searchJob?.cancel()
        if (newQuery.isBlank()) {
            _state.value = UiState.Success(discovery)
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce keystrokes
            runSearch(newQuery)
        }
    }

    private suspend fun runSearch(q: String) {
        _state.value = UiState.Loading
        try {
            _state.value = UiState.Success(repo.search(q).items)
        } catch (e: Exception) {
            _state.value = UiState.Error(e.message ?: "Search failed")
        }
    }
}
