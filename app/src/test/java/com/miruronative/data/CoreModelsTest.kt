package com.miruronative.data

import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.library.mediaListStatusLabel
import com.miruronative.data.library.mergeWatchlistEntries
import com.miruronative.data.library.remoteListStatuses
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.model.MediaRelationConnection
import com.miruronative.data.model.MediaRelationEdge
import com.miruronative.data.model.MediaTag
import com.miruronative.data.model.contentAdvisory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreModelsTest {
    @Test
    fun `anime display titles prefer english over AniList viewer preference`() {
        val title = com.miruronative.data.model.MediaTitle(
            english = "Attack on Titan",
            userPreferred = "Shingeki no Kyojin",
            romaji = "Shingeki no Kyojin",
        )
        assertEquals("Attack on Titan", title.preferred)
    }

    @Test
    fun historyProgressIsBoundedAndEpisodeLabelIsFriendly() {
        val entry = HistoryEntry(
            anilistId = 1,
            title = "Test",
            cover = null,
            episodeNumber = 3.0,
            provider = "bonk",
            category = "sub",
            positionMs = 75_000,
            durationMs = 100_000,
        )

        assertEquals("3", entry.episodeLabel)
        assertEquals(.75f, entry.progressFraction)
        assertTrue(entry.copy(positionMs = 200_000).progressFraction <= 1f)
    }

    @Test
    fun providerCatalogKeepsKnownMiruroProvidersAheadOfNativeFallbacks() {
        assertTrue(ProviderCatalog.sortKey("bonk") < ProviderCatalog.sortKey("anikoto"))
        assertEquals("Bonk", ProviderCatalog.label("bonk"))
        assertTrue(ProviderCatalog.supportsExternalDownloads("bonk"))
        assertTrue(ProviderCatalog.supportsExternalDownloads("kiwi"))
    }

    @Test
    fun contentAdvisoryUsesProminentNonSpoilerTagsAndAdultFlag() {
        val advisory = Media(
            id = 7,
            isAdult = true,
            tags = listOf(
                MediaTag("Gore", rank = 70),
                MediaTag("Profanity", rank = 60),
                MediaTag("Torture", rank = 90, isMediaSpoiler = true),
                MediaTag("Nudity", rank = 20),
            ),
        ).contentAdvisory()

        assertTrue(advisory.isAdult)
        assertEquals(listOf("Violence", "Sexual content", "Strong language", "Mature themes"), advisory.labels)
    }

    @Test
    fun planningHydrationPreservesLocalEntriesAndRefreshesSharedMetadata() {
        val local = listOf(
            WatchlistEntry(1, "Local title", null, addedAt = 10),
            WatchlistEntry(2, "Device only", null, addedAt = 20),
        )
        val remote = listOf(
            WatchlistEntry(1, "AniList title", "cover"),
            WatchlistEntry(3, "Remote only", null),
        )

        val merged = mergeWatchlistEntries(local, remote, addedAt = 99)

        assertEquals(listOf(1, 2, 3), merged.map { it.anilistId })
        assertEquals("AniList title", merged.first().title)
        assertEquals(10, merged.first().addedAt)
        assertEquals(99, merged.last().addedAt)
    }

    @Test
    fun remoteLibrarySnapshotKeepsEveryAniListStatusForDetailPages() {
        val statuses = remoteListStatuses(
            listOf(
                MediaListEntry(status = "CURRENT", media = Media(id = 1)),
                MediaListEntry(status = "PLANNING", media = Media(id = 2)),
                MediaListEntry(status = "COMPLETED", media = Media(id = 3)),
            ),
        )

        assertEquals(mapOf(1 to "CURRENT", 2 to "PLANNING", 3 to "COMPLETED"), statuses)
        assertEquals("Watching", mediaListStatusLabel(statuses[1]))
        assertEquals("Plan to watch", mediaListStatusLabel(statuses[2]))
        assertEquals("Completed", mediaListStatusLabel(statuses[3]))
    }

    @Test
    fun seasonNeighborsKeepsOnlyUniquePrequelsAndSequels() {
        val previous = Media(id = 1)
        val sideStory = Media(id = 2)
        val next = Media(id = 3)
        val root = Media(
            id = 10,
            relations = MediaRelationConnection(
                listOf(
                    MediaRelationEdge("PREQUEL", previous),
                    MediaRelationEdge("SIDE_STORY", sideStory),
                    MediaRelationEdge("SEQUEL", next),
                    MediaRelationEdge("SEQUEL", next),
                ),
            ),
        )

        assertEquals(listOf(1, 3), root.seasonNeighbors().map(Media::id))
    }
}
