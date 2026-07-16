package com.miruronative.ui.watch

import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.ProviderData
import com.miruronative.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSourcePolicyTest {

    @Test
    fun `playback speeds include fine adjustments around normal speed`() {
        assertTrue(PlaybackSpeeds.containsAll(listOf(1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f)))
        assertEquals(PlaybackSpeeds.sorted(), PlaybackSpeeds)
        assertEquals(PlaybackSpeeds.distinct(), PlaybackSpeeds)
        assertEquals("1.15x", 1.15f.formatPlaybackSpeed())
    }
    @Test
    fun `kiwi uses embed because fixed quality CDN streams require page state`() {
        val hls = stream("https://vault.example/1080.m3u8", "hls", "1080p", active = true)
        val embed = stream("https://kwik.example/e/id", "embed", "1080p", active = true)

        assertEquals(embed, pickProviderStream("kiwi", sources(hls, embed)))
    }

    @Test
    fun `ally uses progressive file before unreliable HLS mirror`() {
        val direct = stream("https://files.example/video.mp4", "video", "1080p")
        val hls = stream("https://mirror.example/master.m3u8", "hls", "auto", active = true)

        assertEquals(direct, pickProviderStream("ally", sources(direct, hls)))
    }

    @Test
    fun `other providers retain active HLS preference`() {
        val direct = stream("https://files.example/video.mp4", "video", "1080p")
        val hls = stream("https://cdn.example/master.m3u8", "hls", "auto", active = true)

        assertEquals(hls, pickProviderStream("bonk", sources(direct, hls)))
    }

    @Test
    fun `quality height is recovered from provider label`() {
        assertEquals(1080, declaredVideoHeight("AllAnime 1080p Yt-mp4"))
        assertEquals(720, declaredVideoHeight("720P"))
        assertEquals(null, declaredVideoHeight("AllAnime auto"))
    }

    @Test
    fun `navigation spine removes duplicate rows and orders episode numbers`() {
        val preferred = ProviderData("bonk", listOf(episode(1), episode(2)), emptyList())
        val noisy = ProviderData("hop", listOf(episode(2), episode(1), episode(2), episode(3)), emptyList())

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, noisy)), "bonk", Category.SUB)

        assertEquals(listOf(1.0, 2.0, 3.0), spine.map(EpisodeItem::number))
    }

    @Test
    fun `navigation spine keeps preferred provider on a normalized tie`() {
        val preferredEpisode = episode(1, id = "preferred")
        val preferred = ProviderData("bonk", listOf(preferredEpisode, episode(2)), emptyList())
        val other = ProviderData("hop", listOf(episode(1), episode(2), episode(2)), emptyList())

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, other)), "bonk", Category.SUB)

        assertEquals("preferred", spine.first().pipeId)
    }

    @Test
    fun `anilist progress waits until most of a full integer episode was watched`() {
        assertTrue(shouldSyncAniListProgress(3.0, positionMs = 1_200_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 600_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.5, positionMs = 1_300_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 45_000, durationMs = 50_000))
    }

    private fun sources(vararg streams: StreamItem) = SourcesResult(streams.toList(), emptyList(), null, null)

    private fun stream(url: String, type: String, quality: String, active: Boolean = false) = StreamItem(
        url = url,
        type = type,
        quality = quality,
        audio = null,
        referer = null,
        isActive = active,
        width = null,
        height = null,
    )

    private fun episode(number: Int, id: String = "episode-$number") = EpisodeItem(
        pipeId = id,
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
    )
}
