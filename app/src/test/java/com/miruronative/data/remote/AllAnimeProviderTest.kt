package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.StreamItem
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AllAnimeProviderTest {
    @Test
    fun decodesClockUrlsAndSourceRows() {
        assertEquals("/clock.json", AllAnimeCodec.decodeSourceUrl("--175b54575b53"))

        val root = Json.parseToJsonElement(
            """{"episode":{"sourceUrls":[{"sourceName":"Yt-mp4","sourceUrl":"https://cdn.test/video.mp4","type":"player","resolutionStr":"1080p","priority":10}]}}""",
        )
        val source = AllAnimeCodec.parseSources(root).single()

        assertEquals("Yt-mp4", source.name)
        assertEquals("https://cdn.test/video.mp4", source.url)
        assertEquals("1080p", source.quality)
        assertEquals(10.0, source.priority, 0.0)
    }

    @Test
    fun decryptsAllAnimeAesCtrEnvelope() {
        val plaintext = """{"sourceUrls":[{"sourceName":"S","sourceUrl":"https://cdn.test/master.m3u8","priority":2}]}"""
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val counter = iv + byteArrayOf(0, 0, 0, 2)
        val key = MessageDigest.getInstance("SHA-256").digest("Xot36i3lK3:v1".toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val envelope = byteArrayOf(0) + iv + ciphertext + ByteArray(16) { 7 }
        val encoded = Base64.getEncoder().encodeToString(envelope)

        val source = AllAnimeCodec.parseSources(AllAnimeCodec.decrypt(encoded)).single()

        assertEquals("https://cdn.test/master.m3u8", source.url)
        assertEquals(2.0, source.priority, 0.0)
    }

    @Test
    fun derivesCurrentEpochKeyAndSignsAuthenticatedRequest() {
        val key = AllAnimeCodec.epochKey(
            "AjhjboON3l/6/Y+WLrKww/kcIrmXCFBWUfdl7YHruaA=",
            "cd7f14dbf40734836eb46eb14758e49ef9d81e61686d84d467b2e32063ef4af9",
        )
        assertEquals(
            "cf4777b5778aeadc9449e12769ea545d00c43cd8ff65d482364586cde204f359",
            key.joinToString("") { "%02x".format(it) },
        )

        val signed = AllAnimeCodec.signRequest(key, 4130, "44", "query-hash", 1_784_406_123_456)
        val request = AllAnimeCodec.decryptGcm(signed, key).jsonObject

        assertEquals("1", request.getValue("v").jsonPrimitive.content)
        assertEquals("4130", request.getValue("epoch").jsonPrimitive.content)
        assertEquals("44", request.getValue("buildId").jsonPrimitive.content)
        assertEquals("query-hash", request.getValue("qh").jsonPrimitive.content)
        assertEquals("1784406000000", request.getValue("ts").jsonPrimitive.content)
    }

    @Test
    fun preservesCurrentIframeAndExtensionMetadata() {
        val root = Json.parseToJsonElement(
            """{"episode":{"sourceUrls":[{"sourceName":"Mp4","sourceUrl":"https://mp4upload.com/embed-test.html","type":"iframe","priority":4},{"sourceName":"Yt-mp4","sourceUrl":"https://cdn.test/signed-video?token=x","type":"player","fileExtenstion":"mp4","priority":8}]}}""",
        )
        val sources = AllAnimeCodec.parseSources(root)

        assertEquals("iframe", sources[0].type)
        assertEquals(null, sources[0].fileExtension)
        assertEquals("player", sources[1].type)
        assertEquals("mp4", sources[1].fileExtension)
    }

    @Test
    fun extractsSoftSubtitlesFromEpisodeAndClockPayloads() {
        val root = Json.parseToJsonElement(
            """{
                "episode":{"subtitles":[
                    {"url":"https://cdn.test/en.vtt","label":"English","language":"en"},
                    {"file":"/captions/es.ass","name":"Spanish","lang":"es"}
                ]},
                "tracks":[{"src":"https://cdn.test/signs.srt","kind":"captions","srclang":"ja"}],
                "links":[{"link":"https://cdn.test/video.mp4"}]
            }""".trimIndent(),
        )

        val subtitles = AllAnimeCodec.parseSubtitles(root)

        assertEquals(3, subtitles.size)
        assertEquals(setOf("en", "es", "ja"), subtitles.map { it.language }.toSet())
        assertFalse(subtitles.any { it.url.endsWith("video.mp4") })
    }

    @Test
    fun separatesApiAndPlayerReferersInVersionedConfiguration() {
        val protocol = AllAnimeProtocolConfig.active

        assertEquals("mkissa-build-44", protocol.version)
        assertEquals("https://youtu-chan.com/", protocol.apiReferer)
        assertEquals("https://allanime.day/", protocol.playerReferer)
        assertFalse(protocol.apiReferer == protocol.playerReferer)
    }

    @Test
    fun networkErrorsDoNotPassDirectStreamVerification() {
        val provider = AllAnimeProvider(
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(250))
                .readTimeout(Duration.ofMillis(250))
                .build(),
            Json { ignoreUnknownKeys = true },
        )
        val unreachable = StreamItem(
            url = "http://127.0.0.1:1/unreachable.mp4",
            type = "mp4",
            quality = "test",
            audio = "sub",
            referer = AllAnimeProtocolConfig.active.playerReferer,
            isActive = false,
            width = null,
            height = null,
        )

        assertFalse(provider.isPlayable(unreachable))
    }

    @Test
    fun liveCatalogResolutionAndCurrentSourcesForJujutsuKaisenAndDeathNote() {
        assumeTrue(System.getenv("RUN_LIVE_ALLANIME_TESTS") == "1")
        val provider = AllAnimeProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true },
        )

        val fixtures = listOf(
            CatalogFixture(
                media = Media(
                    id = 1_535,
                    idMal = 1_535,
                    title = MediaTitle(romaji = "Death Note", english = "Death Note"),
                ),
                subCount = 37,
                dubCount = 37,
            ),
            CatalogFixture(
                media = Media(
                    id = 113_415,
                    idMal = 40_748,
                    title = MediaTitle(romaji = "Jujutsu Kaisen", english = "Jujutsu Kaisen"),
                ),
                subCount = 24,
                dubCount = 24,
            ),
        )

        fixtures.forEachIndexed { index, fixture ->
            if (index > 0) Thread.sleep(6_000L)
            val availability = provider.episodeAvailability(fixture.media)
            assertEquals("${fixture.media.title.preferred} Sub count", fixture.subCount, availability.sub.size)
            assertEquals("${fixture.media.title.preferred} Dub count", fixture.dubCount, availability.dub.size)

            listOf("sub", "dub").forEach { audio ->
                val sources = provider.sources(
                    fixture.media,
                    audio,
                    episode = 1,
                    allowLegacyFallback = false,
                )
                assertEquals(AllAnimeProvider.SourceRoute.CURRENT, provider.lastSourceRoute)
                assertTrue("${fixture.media.title.preferred} $audio has no sources", sources.streams.isNotEmpty())
                assertTrue(sources.streams.all { it.audio == audio })
                assertTrue(sources.streams.all { it.referer == AllAnimeProtocolConfig.active.playerReferer })
                assertNotNull(sources.streams.firstOrNull())
            }
        }
    }

    private data class CatalogFixture(
        val media: Media,
        val subCount: Int,
        val dubCount: Int,
    )
}
