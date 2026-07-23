package com.miruronative.data.library

import com.miruronative.data.model.MediaListEntry
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
    /**
     * Seeded from AniList/MAL progress rather than actual playback here. Refreshed wholesale on
     * every remote sync (so remote progress moves it forward), replaced by a real record the
     * moment the user plays the title, and dropped on logout.
     */
    val fromRemote: Boolean = false,
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
    val averageScore: Int? = null,
    val addedAt: Long = 0,
)

/** Persisted snapshot of a title's status on the signed-in list service. */
@Serializable
data class RemoteListStatus(
    val anilistId: Int,
    val status: String,
)

internal fun remoteListStatuses(entries: List<MediaListEntry>): Map<Int, String> = entries
    .mapNotNull { entry ->
        val id = entry.media?.id ?: return@mapNotNull null
        val status = entry.status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        id to status
    }
    .toMap()

internal fun mediaListStatusLabel(status: String?): String? = when (status?.uppercase()) {
    "CURRENT" -> "Watching"
    "REPEATING" -> "Rewatching"
    "PLANNING" -> "Plan to watch"
    "PAUSED" -> "Paused"
    "COMPLETED" -> "Completed"
    "DROPPED" -> "Dropped"
    else -> null
}

internal fun mergeWatchlistEntries(
    local: List<WatchlistEntry>,
    fromAniList: List<WatchlistEntry>,
    addedAt: Long = System.currentTimeMillis(),
): List<WatchlistEntry> {
    if (fromAniList.isEmpty()) return local
    val remoteById = fromAniList.associateBy { it.anilistId }
    val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
    return buildList {
        local.forEach { saved ->
            val remote = remoteById[saved.anilistId]
            add(remote?.copy(addedAt = saved.addedAt) ?: saved)
        }
        fromAniList.forEach { remote ->
            if (remote.anilistId !in localIds) add(remote.copy(addedAt = addedAt))
        }
    }.distinctBy { it.anilistId }
}
