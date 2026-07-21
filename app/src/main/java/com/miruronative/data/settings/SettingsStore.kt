package com.miruronative.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.CrashReporter
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class MenuLanguage(val storedValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SPANISH("es");

    fun usesSpanish(systemLanguage: String = Locale.getDefault().language): Boolean =
        this == SPANISH || (this == SYSTEM && systemLanguage.equals("es", ignoreCase = true))

    companion object {
        fun fromStored(value: String?): MenuLanguage = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

enum class DefaultQuality(val storedValue: String, val label: String) {
    AUTO("auto", "Auto"),
    HIGHEST("highest", "Highest"),
    P1080("1080", "1080p"),
    P720("720", "720p"),
    P480("480", "480p");

    /** Best matching height from [heights], or null to leave adaptive selection alone. */
    fun pickHeight(heights: List<Int>): Int? = when (this) {
        AUTO -> null
        HIGHEST -> heights.maxOrNull()
        // Closest height without going over; a low-quality-only source still plays its best.
        else -> {
            val target = storedValue.toInt()
            heights.filter { it <= target }.maxOrNull() ?: heights.minOrNull()
        }
    }

    companion object {
        fun fromStored(value: String?): DefaultQuality =
            entries.firstOrNull { it.storedValue == value } ?: HIGHEST
    }
}

/**
 * The quality to use before the viewer has chosen one. TV sticks start adaptive: pinning the
 * highest rendition turns ABR off, so a hardware decoder that gets preempted or falls behind has
 * no lower rung to drop to and the picture simply dies. Everything else opens at its sharpest.
 */
private fun deviceDefaultQuality(): DefaultQuality =
    if (AppGraph.isTv) DefaultQuality.AUTO else DefaultQuality.HIGHEST

/** No global server has been chosen yet; the launch route supplies the initial server. */
const val DEFAULT_PREFERRED_PROVIDER = "auto"

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

    private val _updateCheckOnLaunch = MutableStateFlow(true)
    val updateCheckOnLaunch = _updateCheckOnLaunch.asStateFlow()

    // Kept as one compound value rather than a flow per field: both players and the editor read
    // the whole style at once, and a partial style is never meaningful.
    private val _captionStyle = MutableStateFlow(CaptionStyle())
    val captionStyle = _captionStyle.asStateFlow()

    private val _menuLanguage = MutableStateFlow(MenuLanguage.SYSTEM)
    val menuLanguage = _menuLanguage.asStateFlow()

    private val _defaultQuality = MutableStateFlow(deviceDefaultQuality())
    val defaultQuality = _defaultQuality.asStateFlow()

    private val _preferredProvider = MutableStateFlow(DEFAULT_PREFERRED_PROVIDER)
    val preferredProvider = _preferredProvider.asStateFlow()
    private val loaded = MutableStateFlow(false)

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
    fun setUpdateCheckOnLaunch(value: Boolean) = save(UPDATE_CHECK_ON_LAUNCH, value, _updateCheckOnLaunch)

    fun setCaptionBackgroundOpacity(percent: Int) =
        editCaptionStyle { it.copy(backgroundOpacityPercent = percent.coerceIn(0, 100)) }
    fun setCaptionBackgroundColor(value: CaptionBackgroundColor) =
        editCaptionStyle { it.copy(backgroundColor = value) }
    fun setCaptionTextScale(percent: Int) =
        editCaptionStyle { it.copy(textScalePercent = percent.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)) }
    fun setCaptionTextColor(value: CaptionTextColor) = editCaptionStyle { it.copy(textColor = value) }
    fun setCaptionEdgeStyle(value: CaptionEdgeStyle) = editCaptionStyle { it.copy(edgeStyle = value) }
    fun resetCaptionStyle() = editCaptionStyle { CaptionStyle() }

    fun setDefaultQuality(value: DefaultQuality) {
        _defaultQuality.value = value
        scope.launch { store.edit { it[DEFAULT_QUALITY] = value.storedValue } }
    }

    fun setMenuLanguage(value: MenuLanguage) {
        _menuLanguage.value = value
        scope.launch { store.edit { it[MENU_LANGUAGE] = value.storedValue } }
    }
    fun setPreferredProvider(value: String) {
        val storedValue = value.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
        _preferredProvider.value = storedValue
        scope.launch { store.edit { it[PREFERRED_PROVIDER] = storedValue } }
    }

    /** Guarantees cold-start consumers see the persisted preference instead of the in-memory default. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    private fun save(key: Preferences.Key<Boolean>, value: Boolean, state: MutableStateFlow<Boolean>) {
        state.value = value
        scope.launch { store.edit { it[key] = value } }
    }

    private fun editCaptionStyle(transform: (CaptionStyle) -> CaptionStyle) {
        val next = transform(_captionStyle.value)
        _captionStyle.value = next
        scope.launch {
            store.edit { prefs ->
                prefs[CAPTION_BACKGROUND_OPACITY] = next.backgroundOpacityPercent
                prefs[CAPTION_BACKGROUND_COLOR] = next.backgroundColor.storedValue
                prefs[CAPTION_TEXT_SCALE] = next.textScalePercent
                prefs[CAPTION_TEXT_COLOR] = next.textColor.storedValue
                prefs[CAPTION_EDGE_STYLE] = next.edgeStyle.storedValue
            }
        }
    }

    internal fun readCaptionStyle(prefs: Preferences): CaptionStyle = CaptionStyle(
        backgroundOpacityPercent = prefs[CAPTION_BACKGROUND_OPACITY]?.coerceIn(0, 100)
            ?: CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT,
        backgroundColor = CaptionBackgroundColor.fromStored(prefs[CAPTION_BACKGROUND_COLOR]),
        textScalePercent = prefs[CAPTION_TEXT_SCALE]?.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)
            ?: CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT,
        textColor = CaptionTextColor.fromStored(prefs[CAPTION_TEXT_COLOR]),
        edgeStyle = CaptionEdgeStyle.fromStored(prefs[CAPTION_EDGE_STYLE]),
    )

    private fun applyPreferences(prefs: Preferences) {
        _autoplay.value = prefs[AUTOPLAY] ?: true
        _autoSyncAniList.value = prefs[AUTO_SYNC] ?: true
        _preferDub.value = prefs[PREFER_DUB] ?: false
        _releaseNotifications.value = prefs[RELEASE_NOTIFICATIONS] ?: true
        _syncSavedToAniList.value = prefs[SYNC_SAVED_TO_ANILIST] ?: true
        _autoSkipIntroOutro.value = prefs[AUTO_SKIP_INTRO_OUTRO] ?: false
        _hideAdultContent.value = prefs[HIDE_ADULT_CONTENT] ?: true
        _subtitlesWithDub.value = prefs[SUBTITLES_WITH_DUB] ?: false
        _updateCheckOnLaunch.value = prefs[UPDATE_CHECK_ON_LAUNCH] ?: true
        _captionStyle.value = readCaptionStyle(prefs)
        _menuLanguage.value = MenuLanguage.fromStored(prefs[MENU_LANGUAGE])
        _defaultQuality.value = prefs[DEFAULT_QUALITY]?.let(DefaultQuality::fromStored)
            ?: deviceDefaultQuality()
        _preferredProvider.value =
            prefs[PREFERRED_PROVIDER]?.takeIf(String::isNotBlank) ?: DEFAULT_PREFERRED_PROVIDER
        loaded.value = true
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
    private val UPDATE_CHECK_ON_LAUNCH = booleanPreferencesKey("update_check_on_launch")
    private val CAPTION_BACKGROUND_OPACITY = intPreferencesKey("caption_background_opacity")
    private val CAPTION_BACKGROUND_COLOR = stringPreferencesKey("caption_background_color")
    private val CAPTION_TEXT_SCALE = intPreferencesKey("caption_text_scale")
    private val CAPTION_TEXT_COLOR = stringPreferencesKey("caption_text_color")
    private val CAPTION_EDGE_STYLE = stringPreferencesKey("caption_edge_style")
    private val MENU_LANGUAGE = stringPreferencesKey("menu_language")
    private val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
    private val PREFERRED_PROVIDER = stringPreferencesKey("preferred_provider")
    private val MIGRATED = booleanPreferencesKey("migrated_from_shared_preferences")
}
