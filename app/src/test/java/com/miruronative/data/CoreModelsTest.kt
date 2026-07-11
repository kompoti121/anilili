package com.miruronative.data

import com.miruronative.data.library.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreModelsTest {
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
    }
}
