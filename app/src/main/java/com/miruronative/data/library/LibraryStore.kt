package com.miruronative.data.library

import android.content.Context
import android.content.SharedPreferences
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * On-device library: watch history (continue-watching + resume position) and watchlist.
 * Persisted as JSON in SharedPreferences; exposed as StateFlows so the UI reacts. No login.
 */
object LibraryStore {
    private const val KEY_HISTORY = "history"
    private const val KEY_WATCHLIST = "watchlist"
    private const val KEY_REMOTE_STATUSES = "remote_statuses"
    private const val MAX_HISTORY = 100
    private const val REMOTE_REFRESH_INTERVAL_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aniListSyncMutex = Mutex()
    private var remoteRefreshJob: Job? = null
    @Volatile private var lastRemoteRefreshAt = 0L

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val watchlist = _watchlist.asStateFlow()

    private val _remoteStatuses = MutableStateFlow<Map<Int, String>>(emptyMap())
    val remoteStatuses = _remoteStatuses.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("miruro_library", Context.MODE_PRIVATE)
        _history.value = decodeList(prefs.getString(KEY_HISTORY, null), HistoryEntry.serializer())
        _watchlist.value = decodeList(prefs.getString(KEY_WATCHLIST, null), WatchlistEntry.serializer())
        _remoteStatuses.value = decodeList(
            prefs.getString(KEY_REMOTE_STATUSES, null),
            RemoteListStatus.serializer(),
        ).associate { it.anilistId to it.status }
    }

    // ---- history ----

    /** Insert/replace the anime's record (keeps one per anime, most-recent first). */
    fun upsertHistory(entry: HistoryEntry) {
        val stamped = entry.copy(updatedAt = System.currentTimeMillis())
        val updated = buildList {
            add(stamped)
            addAll(_history.value.filter { it.anilistId != entry.anilistId })
        }.take(MAX_HISTORY)
        _history.value = updated
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
        // TV launchers surface in-progress titles in their Continue Watching row; publishing is
        // throttled inside the manager and a no-op off Android TV.
        scope.launch { WatchNextManager.publish(appContext, stamped) }
    }

    fun updateProgress(anilistId: Int, episodeNumber: Double, positionMs: Long, durationMs: Long) {
        val existing = _history.value.firstOrNull { it.anilistId == anilistId } ?: return
        if (existing.episodeNumber != episodeNumber) return
        upsertHistory(existing.copy(positionMs = positionMs, durationMs = durationMs))
    }

    fun historyFor(anilistId: Int): HistoryEntry? = _history.value.firstOrNull { it.anilistId == anilistId }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
        scope.launch { WatchNextManager.removeAll(appContext) }
    }

    // ---- watchlist ----

    fun isInWatchlist(anilistId: Int): Boolean = _watchlist.value.any { it.anilistId == anilistId }

    fun toggleWatchlist(entry: WatchlistEntry) {
        val updated = if (isInWatchlist(entry.anilistId)) {
            _watchlist.value.filter { it.anilistId != entry.anilistId }
        } else {
            listOf(entry.copy(addedAt = System.currentTimeMillis())) + _watchlist.value
        }
        _watchlist.value = updated
        persist(KEY_WATCHLIST, updated, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
        val service = AccountService.active
        // Catalogue-native hanime entries carry a negative, hanime-owned id. Posting one to
        // AniList or MAL would be a request about somebody else's anime, so they never sync.
        val syncable = !com.miruronative.data.remote.isHanimeMediaId(entry.anilistId)
        if (service != null && syncable && SettingsStore.syncSavedToAniList.value) {
            val saved = updated.any { it.anilistId == entry.anilistId }
            scope.launch {
                aniListSyncMutex.withLock {
                    runCatching {
                        when (service) {
                            AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(entry.anilistId, saved)
                            AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(entry.anilistId, saved)
                        }
                    }.onSuccess {
                        refreshRemoteLibrary(force = true)
                    }.onFailure {
                        com.miruronative.diagnostics.DiagnosticsLog.throwable(
                            "${service.label} saved sync failed id=${entry.anilistId} saved=$saved",
                            it,
                        )
                    }
                }
            }
        }
    }

    /** Push the whole device watchlist to whichever list service is signed in. */
    fun syncSavedToRemote() {
        val service = AccountService.active ?: return
        if (!SettingsStore.syncSavedToAniList.value) return
        val savedIds = _watchlist.value.map { it.anilistId }
        scope.launch {
            aniListSyncMutex.withLock {
                runCatching {
                    when (service) {
                        AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(savedIds)
                        AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(savedIds)
                    }
                }.onSuccess {
                    refreshRemoteLibrary(force = true)
                }.onFailure {
                    com.miruronative.diagnostics.DiagnosticsLog.throwable(
                        "${service.label} watchlist sync failed (${savedIds.size} titles)",
                        it,
                    )
                }
            }
        }
    }

    /**
     * Bulk add from a MAL XML import. Existing saves keep their position and addedAt; the
     * whole batch syncs to the signed-in list service in one push rather than per title.
     * Returns how many entries were actually new.
     */
    fun importWatchlist(entries: List<WatchlistEntry>): Int {
        val current = _watchlist.value
        val existing = current.mapTo(mutableSetOf()) { it.anilistId }
        val now = System.currentTimeMillis()
        val newEntries = entries
            .distinctBy { it.anilistId }
            .filter { it.anilistId !in existing }
            .map { it.copy(addedAt = now) }
        if (newEntries.isEmpty()) return 0
        val updated = newEntries + current
        _watchlist.value = updated
        persist(KEY_WATCHLIST, updated, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
        syncSavedToRemote()
        return newEntries.size
    }

    /** Merge AniList Planning into this device without deleting device-only saves. */
    fun hydrateWatchlistFromAniList(entries: List<WatchlistEntry>) {
        val merged = mergeWatchlistEntries(_watchlist.value, entries)
        if (merged == _watchlist.value) return
        _watchlist.value = merged
        persist(KEY_WATCHLIST, merged, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
    }

    /**
     * Publish a freshly fetched remote collection to every screen immediately. Unlike the local
     * watchlist, this includes every list state so Detail can distinguish Watching, Completed,
     * Paused, and Dropped from a title that truly is not tracked.
     */
    fun hydrateRemoteLibrary(entries: List<MediaListEntry>) {
        val statuses = remoteListStatuses(entries)
        _remoteStatuses.value = statuses
        persist(
            KEY_REMOTE_STATUSES,
            statuses.map { (id, status) -> RemoteListStatus(id, status) },
            RemoteListStatus.serializer(),
        )
        lastRemoteRefreshAt = System.currentTimeMillis()
        seedHistoryFromRemote(entries)

        if (SettingsStore.syncSavedToAniList.value) {
            hydrateWatchlistFromAniList(
                entries.mapNotNull { entry ->
                    if (entry.status != "PLANNING") return@mapNotNull null
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
    }

    /**
     * Continue Watching from the signed-in service: every title it says the user is watching
     * becomes a resumable row pointing at the next unwatched episode, so a fresh install isn't
     * blank. Real playback records always win — seeded rows sit behind them, are replaced
     * wholesale on every refresh (remote progress moves them forward), get superseded by a real
     * record the moment the title is played here, and vanish on logout. The "auto" provider is
     * the same sentinel Watch Now uses when nothing local exists: the watch screen resolves a
     * source itself.
     */
    private fun seedHistoryFromRemote(entries: List<MediaListEntry>) {
        val preferDub = SettingsStore.preferDub.value
        val local = _history.value.filterNot { it.fromRemote }
        val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
        val seeded = entries.mapNotNull { entry ->
            if (entry.status != "CURRENT" && entry.status != "REPEATING") return@mapNotNull null
            val media = entry.media ?: return@mapNotNull null
            if (media.id in localIds) return@mapNotNull null
            val nextEpisode = entry.progress + 1
            val total = media.episodes
            if (total != null && total > 0 && nextEpisode > total) return@mapNotNull null
            HistoryEntry(
                anilistId = media.id,
                title = media.title.preferred,
                cover = media.coverImage.best,
                episodeNumber = nextEpisode.toDouble(),
                provider = "auto",
                category = if (preferDub) "dub" else "sub",
                fromRemote = true,
            )
        }
        val updated = (local + seeded).take(MAX_HISTORY)
        if (updated == _history.value) return
        _history.value = updated
        // No WatchNextManager publish for seeded rows: the launcher's Continue Watching channel
        // is reserved for titles actually played on this device.
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
    }

    /** Update the snapshot synchronously after playback changes an AniList/MAL list state. */
    fun updateRemoteStatus(anilistId: Int, status: String?) {
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val updated = _remoteStatuses.value.toMutableMap().apply {
            if (normalized == null) remove(anilistId) else put(anilistId, normalized)
        }
        _remoteStatuses.value = updated
        persist(
            KEY_REMOTE_STATUSES,
            updated.map { (id, value) -> RemoteListStatus(id, value) },
            RemoteListStatus.serializer(),
        )
    }

    /** Refresh on cold start/foreground, while bounding AniList's shared API rate limit. */
    @Synchronized
    fun refreshRemoteLibrary(force: Boolean = false) {
        val service = AccountService.active
        if (service == null) {
            if (_remoteStatuses.value.isNotEmpty()) clearRemoteLibrary()
            return
        }
        val now = System.currentTimeMillis()
        if (remoteRefreshJob?.isActive == true) return
        if (!force && now - lastRemoteRefreshAt < REMOTE_REFRESH_INTERVAL_MS) return
        lastRemoteRefreshAt = now
        remoteRefreshJob = scope.launch {
            runCatching {
                aniListSyncMutex.withLock {
                    val entries = when (service) {
                        AccountService.ANILIST -> {
                            val viewerId = AuthManager.viewerId() ?: AppGraph.repository.viewer()?.id
                                ?: error("Couldn't load your AniList account")
                            val collection = AppGraph.repository.userAnimeList(viewerId)
                                ?: error("Couldn't load your AniList library")
                            collection.lists
                                .flatMap { it.entries }
                                .distinctBy { it.id }
                        }
                        AccountService.MAL -> AppGraph.repository.malAnimeList()
                    }
                    hydrateRemoteLibrary(entries)
                    com.miruronative.diagnostics.DiagnosticsLog.event(
                        "${service.label} library refreshed statuses=${_remoteStatuses.value.size}",
                    )
                }
            }.onFailure {
                lastRemoteRefreshAt = 0L
                com.miruronative.diagnostics.DiagnosticsLog.throwable(
                    "${service.label} library refresh failed",
                    it,
                )
            }
        }
    }

    fun clearRemoteLibrary() {
        remoteRefreshJob?.cancel()
        remoteRefreshJob = null
        lastRemoteRefreshAt = 0L
        _remoteStatuses.value = emptyMap()
        prefs.edit().remove(KEY_REMOTE_STATUSES).apply()
        // Seeded Continue Watching rows belong to the account that just signed out.
        val localOnly = _history.value.filterNot { it.fromRemote }
        if (localOnly != _history.value) {
            _history.value = localOnly
            persist(KEY_HISTORY, localOnly, HistoryEntry.serializer())
        }
    }

    // ---- persistence ----

    private fun <T> persist(key: String, list: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer), list)
        prefs.edit().putString(key, encoded).apply()
    }

    private fun <T> decodeList(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer), raw)
        }.getOrDefault(emptyList())
    }
}
