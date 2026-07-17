package com.miruronative.data.library

import android.content.Context
import android.content.SharedPreferences
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private const val MAX_HISTORY = 100

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aniListSyncMutex = Mutex()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val watchlist = _watchlist.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("miruro_library", Context.MODE_PRIVATE)
        _history.value = decodeList(prefs.getString(KEY_HISTORY, null), HistoryEntry.serializer())
        _watchlist.value = decodeList(prefs.getString(KEY_WATCHLIST, null), WatchlistEntry.serializer())
    }

    // ---- history ----

    /** Insert/replace the anime's record (keeps one per anime, most-recent first). */
    fun upsertHistory(entry: HistoryEntry) {
        val updated = buildList {
            add(entry.copy(updatedAt = System.currentTimeMillis()))
            addAll(_history.value.filter { it.anilistId != entry.anilistId })
        }.take(MAX_HISTORY)
        _history.value = updated
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
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
        if (service != null && SettingsStore.syncSavedToAniList.value) {
            val saved = updated.any { it.anilistId == entry.anilistId }
            scope.launch {
                aniListSyncMutex.withLock {
                    runCatching {
                        when (service) {
                            AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(entry.anilistId, saved)
                            AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(entry.anilistId, saved)
                        }
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
                }.onFailure {
                    com.miruronative.diagnostics.DiagnosticsLog.throwable(
                        "${service.label} watchlist sync failed (${savedIds.size} titles)",
                        it,
                    )
                }
            }
        }
    }

    /** Merge AniList Planning into this device without deleting device-only saves. */
    fun hydrateWatchlistFromAniList(entries: List<WatchlistEntry>) {
        val merged = mergeWatchlistEntries(_watchlist.value, entries)
        if (merged == _watchlist.value) return
        _watchlist.value = merged
        persist(KEY_WATCHLIST, merged, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
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
