package com.miruronative.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small persistent preference store shared by playback and the Library settings UI. */
object SettingsStore {
    private lateinit var prefs: SharedPreferences

    private val _autoplay = MutableStateFlow(true)
    val autoplay = _autoplay.asStateFlow()

    private val _autoSyncAniList = MutableStateFlow(true)
    val autoSyncAniList = _autoSyncAniList.asStateFlow()

    private val _preferDub = MutableStateFlow(false)
    val preferDub = _preferDub.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("anilili_settings", Context.MODE_PRIVATE)
        _autoplay.value = prefs.getBoolean(KEY_AUTOPLAY, true)
        _autoSyncAniList.value = prefs.getBoolean(KEY_AUTO_SYNC, true)
        _preferDub.value = prefs.getBoolean(KEY_PREFER_DUB, false)
    }

    fun setAutoplay(value: Boolean) = save(KEY_AUTOPLAY, value, _autoplay)
    fun setAutoSyncAniList(value: Boolean) = save(KEY_AUTO_SYNC, value, _autoSyncAniList)
    fun setPreferDub(value: Boolean) = save(KEY_PREFER_DUB, value, _preferDub)

    private fun save(key: String, value: Boolean, state: MutableStateFlow<Boolean>) {
        state.value = value
        prefs.edit().putBoolean(key, value).apply()
    }

    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_AUTO_SYNC = "auto_sync_anilist"
    private const val KEY_PREFER_DUB = "prefer_dub"
}
