package com.miruronative.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.miruronative.diagnostics.CrashReporter
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Transactional DataStore preferences shared by playback and the Library settings UI. */
object SettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DataStore<Preferences>

    private val _autoplay = MutableStateFlow(true)
    val autoplay = _autoplay.asStateFlow()

    private val _autoSyncAniList = MutableStateFlow(true)
    val autoSyncAniList = _autoSyncAniList.asStateFlow()

    private val _preferDub = MutableStateFlow(false)
    val preferDub = _preferDub.asStateFlow()

    private val _releaseNotifications = MutableStateFlow(true)
    val releaseNotifications = _releaseNotifications.asStateFlow()

    private val _syncSavedToAniList = MutableStateFlow(true)
    val syncSavedToAniList = _syncSavedToAniList.asStateFlow()

    private val _autoSkipIntroOutro = MutableStateFlow(false)
    val autoSkipIntroOutro = _autoSkipIntroOutro.asStateFlow()

    private val _hideAdultContent = MutableStateFlow(true)
    val hideAdultContent = _hideAdultContent.asStateFlow()

    private val _subtitlesWithDub = MutableStateFlow(false)
    val subtitlesWithDub = _subtitlesWithDub.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { app.preferencesDataStoreFile("anilili_settings") },
        )
        scope.launch {
            runCatching { migrateLegacyPreferences(app) }
                .onFailure { CrashReporter.logNonFatal("Settings migration failed", it) }
            store.data
                .catch { error ->
                    // A settings read must never take the process down; fall back to defaults.
                    if (error !is IOException) CrashReporter.logNonFatal("Settings read failed", error)
                    emit(emptyPreferences())
                }
                .collect(::applyPreferences)
        }
    }

    fun setAutoplay(value: Boolean) = save(AUTOPLAY, value, _autoplay)
    fun setAutoSyncAniList(value: Boolean) = save(AUTO_SYNC, value, _autoSyncAniList)
    fun setPreferDub(value: Boolean) = save(PREFER_DUB, value, _preferDub)
    fun setReleaseNotifications(value: Boolean) = save(RELEASE_NOTIFICATIONS, value, _releaseNotifications)
    fun setSyncSavedToAniList(value: Boolean) = save(SYNC_SAVED_TO_ANILIST, value, _syncSavedToAniList)
    fun setAutoSkipIntroOutro(value: Boolean) = save(AUTO_SKIP_INTRO_OUTRO, value, _autoSkipIntroOutro)
    fun setHideAdultContent(value: Boolean) = save(HIDE_ADULT_CONTENT, value, _hideAdultContent)
    fun setSubtitlesWithDub(value: Boolean) = save(SUBTITLES_WITH_DUB, value, _subtitlesWithDub)

    private fun save(key: Preferences.Key<Boolean>, value: Boolean, state: MutableStateFlow<Boolean>) {
        state.value = value
        scope.launch { store.edit { it[key] = value } }
    }

    private fun applyPreferences(prefs: Preferences) {
        _autoplay.value = prefs[AUTOPLAY] ?: true
        _autoSyncAniList.value = prefs[AUTO_SYNC] ?: true
        _preferDub.value = prefs[PREFER_DUB] ?: false
        _releaseNotifications.value = prefs[RELEASE_NOTIFICATIONS] ?: true
        _syncSavedToAniList.value = prefs[SYNC_SAVED_TO_ANILIST] ?: true
        _autoSkipIntroOutro.value = prefs[AUTO_SKIP_INTRO_OUTRO] ?: false
        _hideAdultContent.value = prefs[HIDE_ADULT_CONTENT] ?: true
        _subtitlesWithDub.value = prefs[SUBTITLES_WITH_DUB] ?: false
    }

    private suspend fun migrateLegacyPreferences(context: Context) {
        val current = store.data.first()
        if (current[MIGRATED] == true) return
        val legacy = context.getSharedPreferences("anilili_settings", Context.MODE_PRIVATE)
        store.edit { prefs ->
            prefs[AUTOPLAY] = legacy.getBoolean("autoplay", true)
            prefs[AUTO_SYNC] = legacy.getBoolean("auto_sync_anilist", true)
            prefs[PREFER_DUB] = legacy.getBoolean("prefer_dub", false)
            prefs[RELEASE_NOTIFICATIONS] = true
            prefs[SYNC_SAVED_TO_ANILIST] = true
            prefs[AUTO_SKIP_INTRO_OUTRO] = false
            prefs[MIGRATED] = true
        }
        legacy.edit().clear().apply()
    }

    private val AUTOPLAY = booleanPreferencesKey("autoplay")
    private val AUTO_SYNC = booleanPreferencesKey("auto_sync_anilist")
    private val PREFER_DUB = booleanPreferencesKey("prefer_dub")
    private val RELEASE_NOTIFICATIONS = booleanPreferencesKey("release_notifications")
    private val SYNC_SAVED_TO_ANILIST = booleanPreferencesKey("sync_saved_to_anilist")
    private val AUTO_SKIP_INTRO_OUTRO = booleanPreferencesKey("auto_skip_intro_outro")
    private val HIDE_ADULT_CONTENT = booleanPreferencesKey("hide_adult_content")
    private val SUBTITLES_WITH_DUB = booleanPreferencesKey("subtitles_with_dub")
    private val MIGRATED = booleanPreferencesKey("migrated_from_shared_preferences")
}
