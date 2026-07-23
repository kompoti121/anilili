package com.miruronative.data.library

import android.content.Context
import android.content.SharedPreferences
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The user's own past search terms, most-recent first. Local-only (no account), persisted as a
 * JSON string list in SharedPreferences and exposed as a StateFlow so the search screen reacts.
 * Mirrors [LibraryStore]'s shape deliberately.
 */
object SearchHistoryStore {
    private const val PREFS = "anilili_search_history"
    private const val KEY = "queries"
    private const val MAX_ENTRIES = 12

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history = _history.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _history.value = runCatching {
            prefs.getString(KEY, null)?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
        }.getOrNull().orEmpty()
    }

    /**
     * Record a term the user actually searched. Blank and whitespace-only terms are ignored; an
     * existing entry moves to the front rather than duplicating, so the list reads as "most recent"
     * without the same query appearing twice.
     */
    fun record(query: String) {
        val term = query.trim()
        if (term.isEmpty() || !::prefs.isInitialized) return
        val next = (listOf(term) + _history.value.filterNot { it.equals(term, ignoreCase = true) })
            .take(MAX_ENTRIES)
        if (next == _history.value) return
        _history.value = next
        persist(next)
    }

    fun remove(query: String) {
        if (!::prefs.isInitialized) return
        val next = _history.value.filterNot { it.equals(query.trim(), ignoreCase = true) }
        if (next == _history.value) return
        _history.value = next
        persist(next)
    }

    fun clear() {
        if (!::prefs.isInitialized) return
        _history.value = emptyList()
        persist(emptyList())
    }

    private fun persist(entries: List<String>) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(ListSerializer(String.serializer()), entries)).apply()
        }.onFailure { DiagnosticsLog.throwable("SearchHistoryStore persist failed", it) }
    }
}
