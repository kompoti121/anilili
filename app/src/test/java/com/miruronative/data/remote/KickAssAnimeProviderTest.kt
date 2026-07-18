package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTitle
import java.time.Duration
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KickAssAnimeProviderTest {
    @Test
    fun parsesCatalogEpisodesPlayerAndSoftSubtitles() {
        val search = Json.parseToJsonElement(
            """{"result":[{"slug":"show-1234","title":"Show","title_en":"Show EN","locales":["ja-JP","en-US"]}]}""",
        )
        val candidate = KickAssAnimeCodec.searchResults(search).single()
        assertEquals("show-1234", candidate.slug)
        assertEquals("Show EN", candidate.title)
        assertEquals(setOf("ja-JP", "en-US"), candidate.locales)
        assertEquals(null, candidate.year)

        val episodes = Json.parseToJsonElement(
            """{"pages":[{"eps":[1,2,3]}],"result":[{"slug":"abc","episode_number":1,"title":"Pilot"}]}""",
        )
        assertEquals(setOf(1, 2, 3), KickAssAnimeCodec.episodeNumbers(episodes))
        assertEquals("abc", KickAssAnimeCodec.episode(episodes, 1)?.slug)

        val detail = Json.parseToJsonElement(
            """{"servers":[{"name":"BirdStream","src":"https://example.test/dash"},{"name":"VidStreaming","src":"https://krussdomi.com/cat-player/player?id=media-id&source=vidstream"}]}""",
        )
        val player = KickAssAnimeCodec.vidStreamingUrl(detail)
        assertNotNull(player)
        assertEquals("media-id", KickAssAnimeCodec.playerId(player!!))

        val html = """<astro-island props="{&quot;language&quot;:[0,&quot;eng&quot;],&quot;name&quot;:[0,&quot;English&quot;],&quot;src&quot;:[0,&quot;https://subs.test/en.vtt&quot;]}"></astro-island>"""
        val subtitle = KickAssAnimeCodec.subtitles(html).single()
        assertEquals("English", subtitle.label)
        assertEquals("eng", subtitle.language)
        assertEquals("https://subs.test/en.vtt", subtitle.url)
    }

    @Test
    fun liveJujutsuKaisenAndDeathNoteHaveExactSubDubCountsAndPlayableMultiAudioHls() {
        assumeTrue(System.getenv("RUN_LIVE_KAA_TESTS") == "1")
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .callTimeout(Duration.ofSeconds(30))
            .build()
        val provider = KickAssAnimeProvider(client, Json { ignoreUnknownKeys = true })
        val fixtures = listOf(
            Fixture(
                Media(
                    id = 113_415,
                    idMal = 40_748,
                    title = MediaTitle(romaji = "Jujutsu Kaisen", english = "Jujutsu Kaisen"),
                    seasonYear = 2020,
                ),
                24,
            ),
            Fixture(
                Media(
                    id = 1_535,
                    idMal = 1_535,
                    title = MediaTitle(romaji = "Death Note", english = "Death Note"),
                    seasonYear = 2006,
                ),
                37,
            ),
        )

        fixtures.forEach { fixture ->
            val availability = provider.episodeAvailability(fixture.media)
            assertEquals("${fixture.media.title.preferred} Sub", fixture.episodes, availability.sub.size)
            assertEquals("${fixture.media.title.preferred} Dub", fixture.episodes, availability.dub.size)

            val sub = provider.sources(fixture.media, "sub", 1)
            val dub = provider.sources(fixture.media, "dub", 1)
            assertTrue(sub.streams.single().isHls)
            assertTrue(dub.streams.single().isHls)
            assertEquals(sub.streams.single().url, dub.streams.single().url)
            assertEquals("${KickAssAnimeProvider.PLAYER_ORIGIN}/", sub.streams.single().referer)
            assertEquals("sub", sub.streams.single().audio)
            assertEquals("dub", dub.streams.single().audio)
            assertTrue("${fixture.media.title.preferred} soft subtitles missing", sub.subtitles.isNotEmpty())

            val masterRequest = Request.Builder()
                .url(sub.streams.single().url)
                .header("Referer", "${KickAssAnimeProvider.PLAYER_ORIGIN}/")
                .header("Origin", KickAssAnimeProvider.PLAYER_ORIGIN)
                .build()
            client.newCall(masterRequest).execute().use { response ->
                val master = response.body?.string().orEmpty()
                assertTrue("${fixture.media.title.preferred} master HTTP ${response.code}", response.isSuccessful)
                assertTrue(master.startsWith("#EXTM3U"))
                assertTrue(master.contains("LANGUAGE=\"jpn\""))
                assertTrue(master.contains("LANGUAGE=\"eng\""))
                assertTrue(master.contains("RESOLUTION=1920x1080"))
            }
        }
    }

    private data class Fixture(val media: Media, val episodes: Int)
}
