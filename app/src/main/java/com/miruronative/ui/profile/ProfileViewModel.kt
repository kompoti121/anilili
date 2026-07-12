package com.miruronative.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.model.Viewer
import com.miruronative.data.library.LibraryStore
import com.miruronative.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AniListProfile(
    val viewer: Viewer,
    val watching: List<MediaListEntry>,
    val rewatching: List<MediaListEntry>,
    val planning: List<MediaListEntry>,
    val paused: List<MediaListEntry>,
    val completed: List<MediaListEntry>,
    val dropped: List<MediaListEntry>,
)

class ProfileViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _profile = MutableStateFlow<UiState<AniListProfile>?>(null)
    val profile = _profile.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun loadIfLoggedIn(refresh: Boolean = false) {
        if (!AuthManager.isLoggedIn) {
            _profile.value = null
            return
        }
        viewModelScope.launch {
            if (refresh && _profile.value is UiState.Success) _isRefreshing.value = true else _profile.value = UiState.Loading
            try {
                val viewer = repo.viewer() ?: error("Couldn't load your AniList profile")
                val lists = repo.userAnimeList(viewer.id)?.lists ?: emptyList()
                val watching = lists.filter { it.status == "CURRENT" }.flatMap { it.entries }
                val rewatching = lists.filter { it.status == "REPEATING" }.flatMap { it.entries }
                val planning = lists.filter { it.status == "PLANNING" }.flatMap { it.entries }
                val paused = lists.filter { it.status == "PAUSED" }.flatMap { it.entries }
                val completed = lists.filter { it.status == "COMPLETED" }.flatMap { it.entries }
                val dropped = lists.filter { it.status == "DROPPED" }.flatMap { it.entries }
                _profile.value = UiState.Success(
                    AniListProfile(viewer, watching, rewatching, planning, paused, completed, dropped),
                )
            } catch (e: Exception) {
                _profile.value = UiState.Error(e.message ?: "Failed to load AniList")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() = loadIfLoggedIn(refresh = true)

    fun onLoggedIn(token: String) {
        AuthManager.setToken(token)
        LibraryStore.syncSavedToAniList()
        loadIfLoggedIn()
    }

    fun logout() {
        AuthManager.logout()
        _profile.value = null
    }
}
