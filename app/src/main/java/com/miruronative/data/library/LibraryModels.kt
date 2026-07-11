package com.miruronative.data.library

import kotlinx.serialization.Serializable

/** One "continue watching" record per anime — the last episode watched + resume position. */
@Serializable
data class HistoryEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val episodeNumber: Double,
    val episodeTitle: String? = null,
    val provider: String,
    val category: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val episodeLabel: String
        get() = if (episodeNumber % 1.0 == 0.0) episodeNumber.toInt().toString() else episodeNumber.toString()
}

/** A saved series the user wants to watch. */
@Serializable
data class WatchlistEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val format: String? = null,
    val addedAt: Long = 0,
)
