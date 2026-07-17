package com.miruronative.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.auth.MalAuthManager
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.model.MediaListCollection
import com.miruronative.data.model.Viewer
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.MalExport
import com.miruronative.data.library.MalExportEntry
import com.miruronative.data.library.MalExportFile
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class AniListProfile(
    val viewer: Viewer,
    val watching: List<MediaListEntry>,
    val rewatching: List<MediaListEntry>,
    val planning: List<MediaListEntry>,
    val paused: List<MediaListEntry>,
    val completed: List<MediaListEntry>,
    val dropped: List<MediaListEntry>,
    /** Which service these lists came from; AniList and MAL entries share this shape. */
    val service: AccountService = AccountService.ANILIST,
)

class ProfileViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _profile = MutableStateFlow<UiState<AniListProfile>?>(null)
    val profile = _profile.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun loadIfLoggedIn(refresh: Boolean = false) {
        val service = AccountService.active
        if (service == null) {
            _profile.value = null
            return
        }
        viewModelScope.launch {
            if (refresh && _profile.value is UiState.Success) _isRefreshing.value = true else _profile.value = UiState.Loading
            try {
                val (viewer, entries) = when (service) {
                    AccountService.ANILIST -> {
                        val viewer = repo.viewer() ?: error("Couldn't load your AniList profile")
                        viewer to repo.userAnimeList(viewer.id).allEntries()
                    }
                    AccountService.MAL -> repo.malViewer() to repo.malAnimeList()
                }
                val watching = entries.filter { it.status == "CURRENT" }
                val rewatching = entries.filter { it.status == "REPEATING" }
                val planning = entries.filter { it.status == "PLANNING" }
                val paused = entries.filter { it.status == "PAUSED" }
                val completed = entries.filter { it.status == "COMPLETED" }
                val dropped = entries.filter { it.status == "DROPPED" }
                if (SettingsStore.syncSavedToAniList.value) {
                    LibraryStore.hydrateWatchlistFromAniList(
                        planning.mapNotNull { entry ->
                            val media = entry.media ?: return@mapNotNull null
                            WatchlistEntry(
                                anilistId = media.id,
                                title = media.title.preferred,
                                cover = media.coverImage.best,
                                format = media.format,
                                averageScore = media.averageScore,
                            )
                        },
                    )
                }
                _profile.value = UiState.Success(
                    AniListProfile(viewer, watching, rewatching, planning, paused, completed, dropped, service),
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _profile.value = UiState.Error(e.message ?: "Failed to load ${service.label}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() = loadIfLoggedIn(refresh = true)

    fun onLoggedIn(token: String) {
        AuthManager.setToken(token)
        LibraryStore.syncSavedToRemote()
        loadIfLoggedIn()
    }

    /** MAL redirect hands back a code; trade it for tokens before loading the profile. */
    fun onMalCode(code: String) {
        _profile.value = UiState.Loading
        viewModelScope.launch {
            try {
                MalAuthManager.exchangeCode(code)
                LibraryStore.syncSavedToRemote()
                loadIfLoggedIn()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                MalAuthManager.logout()
                _profile.value = UiState.Error(e.message ?: "MyAnimeList login failed")
            }
        }
    }

    fun logout() {
        AuthManager.logout()
        MalAuthManager.logout()
        _profile.value = null
    }

    suspend fun buildMalExport(
        profile: AniListProfile?,
        watchlist: List<WatchlistEntry>,
        history: List<HistoryEntry>,
    ): MalExportFile = withContext(Dispatchers.IO) {
        val entries = LinkedHashMap<Int, MalExportEntry>()
        var skipped = 0

        suspend fun addMediaList(entry: MediaListEntry) {
            val media = entry.media ?: run {
                skipped++
                return
            }
            val resolved = if (media.idMal != null) media else repo.animeInfo(media.id) ?: media
            val (status, rewatching) = MalExport.statusFromAniList(entry.status)
            val exportEntry = MalExport.entryFromMedia(
                media = resolved,
                status = status,
                progress = entry.progress,
                score = entry.score,
                rewatching = rewatching,
            )
            if (exportEntry == null) skipped++ else entries[resolved.id] = exportEntry
        }

        listOf(
            profile?.watching.orEmpty(),
            profile?.rewatching.orEmpty(),
            profile?.completed.orEmpty(),
            profile?.paused.orEmpty(),
            profile?.dropped.orEmpty(),
            profile?.planning.orEmpty(),
        ).flatten().forEach { addMediaList(it) }

        val historyById = history.associateBy { it.anilistId }
        watchlist.forEach { saved ->
            if (entries.containsKey(saved.anilistId)) return@forEach
            val media = repo.animeInfo(saved.anilistId) ?: run {
                skipped++
                return@forEach
            }
            val progress = historyById[saved.anilistId]?.episodeNumber?.toInt() ?: 0
            val exportEntry = MalExport.entryFromMedia(
                media = media,
                status = MalExport.statusFromLocal(progress, media.episodes),
                progress = progress,
            )
            if (exportEntry == null) skipped++ else entries[saved.anilistId] = exportEntry
        }

        history.forEach { item ->
            if (entries.containsKey(item.anilistId)) return@forEach
            val media = repo.animeInfo(item.anilistId) ?: run {
                skipped++
                return@forEach
            }
            val progress = item.episodeNumber.toInt()
            val exportEntry = MalExport.entryFromMedia(
                media = media,
                status = MalExport.statusFromLocal(progress, media.episodes),
                progress = progress,
            )
            if (exportEntry == null) skipped++ else entries[item.anilistId] = exportEntry
        }

        MalExport.fromEntries(profile?.viewer?.name, entries.values.toList(), skipped)
    }
}

/** Custom-list-only entries are duplicated across groups; flatten and classify by entry status. */
internal fun MediaListCollection?.allEntries(): List<MediaListEntry> = this?.lists.orEmpty()
    .flatMap { it.entries }
    .distinctBy { it.id }
