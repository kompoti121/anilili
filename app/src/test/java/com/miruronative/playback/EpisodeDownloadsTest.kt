package com.miruronative.playback

import com.miruronative.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloadsTest {
    @Test
    fun `download id separates audio categories and normalizes case`() {
        assertEquals(
            "episode:42:dub:1.5",
            EpisodeDownloads.idFor(42, " DUB ", "1.5"),
        )
        assertEquals(
            "episode:42:sub:1.5",
            EpisodeDownloads.idFor(42, "sub", "1.5"),
        )
    }

    @Test
    fun `only native hls without playlist rewriting is downloadable`() {
        assertTrue(EpisodeDownloads.canDownload(stream("https://cdn.example/episode/master.m3u8", "hls")))
        assertFalse(EpisodeDownloads.canDownload(stream("https://embed.example/watch/1", "embed")))
        assertFalse(EpisodeDownloads.canDownload(stream("https://cdn.example/episode.mp4", "video/mp4")))
        assertFalse(
            EpisodeDownloads.canDownload(
                stream("https://flixcloud.example/master.m3u8", "hls", playlistKey = "secret"),
            ),
        )
    }

    private fun stream(
        url: String,
        type: String,
        playlistKey: String? = null,
    ) = StreamItem(
        url = url,
        type = type,
        quality = "auto",
        audio = "sub",
        referer = "https://provider.example/",
        isActive = true,
        width = null,
        height = null,
        playlistKey = playlistKey,
    )
}
