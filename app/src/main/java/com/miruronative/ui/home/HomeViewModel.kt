package com.miruronative.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    NEWEST("NEWEST"),
    POPULAR("POPULAR"),
    TOP_RATED("TOP RATED"),
}

data class HomeData(
    val spotlight: List<Media>,
    val newest: List<Media>,
    val popular: List<Media>,
    val topRated: List<Media>,
) {
    fun tab(tab: HomeTab): List<Media> = when (tab) {
        HomeTab.NEWEST -> newest
        HomeTab.POPULAR -> popular
        HomeTab.TOP_RATED -> topRated
    }
}

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    var selectedTab by mutableStateOf(HomeTab.POPULAR)
        private set

    init { load() }

    fun selectTab(tab: HomeTab) { selectedTab = tab }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                val data = coroutineScope {
                    val spotlight = async { repo.trending(force = force).items }
                    val newest = async { repo.recentlyReleased(force = force).items }
                    val popular = async { repo.popular(force = force).items }
                    val topRated = async { repo.topRated(force = force).items }
                    HomeData(spotlight.await(), newest.await(), popular.await(), topRated.await())
                }
                _state.value = UiState.Success(data)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Failed to load home")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() = load(force = true)
}
