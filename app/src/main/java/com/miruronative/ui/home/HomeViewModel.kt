package com.miruronative.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
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

    var selectedTab by mutableStateOf(HomeTab.POPULAR)
        private set

    init { load() }

    fun selectTab(tab: HomeTab) { selectedTab = tab }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val data = coroutineScope {
                    val spotlight = async { repo.trending().items }
                    val newest = async { repo.recentlyReleased().items }
                    val popular = async { repo.popular().items }
                    val topRated = async { repo.topRated().items }
                    HomeData(spotlight.await(), newest.await(), popular.await(), topRated.await())
                }
                _state.value = UiState.Success(data)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load home")
            }
        }
    }
}
