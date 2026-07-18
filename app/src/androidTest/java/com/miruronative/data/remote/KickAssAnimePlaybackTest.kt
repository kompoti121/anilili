package com.miruronative.data.remote

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.StreamItem
import com.miruronative.ui.watch.applyCategoryAudioPreference
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live Media3 smoke test. STATE_READY proves the emulator fetched and buffered CDN segments. */
@RunWith(AndroidJUnit4::class)
class KickAssAnimePlaybackTest {
    @Test
    fun jujutsuKaisenSubAndDubBufferWithTheRequestedAudioOnMedia3() {
        val provider = KickAssAnimeProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true },
        )
        val media = Media(
            id = 113_415,
            idMal = 40_748,
            title = MediaTitle(romaji = "Jujutsu Kaisen", english = "Jujutsu Kaisen"),
            seasonYear = 2020,
        )
        val sub = provider.sources(media, "sub", 1)
        val dub = provider.sources(media, "dub", 1)

        assertTrue("KAA soft subtitles were not extracted", sub.subtitles.size >= 2)
        assertEquals(sub.streams.single().url, dub.streams.single().url)
        // Media3 normalizes the manifest's ISO-639-2 jpn/eng tags to ja/en.
        assertEquals("ja", bufferAndSelect(sub.streams.single(), "sub").selectedLanguage)
        assertEquals("en", bufferAndSelect(dub.streams.single(), "dub").selectedLanguage)
    }

    private fun bufferAndSelect(stream: StreamItem, category: String): PlaybackProbe {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val completed = CountDownLatch(1)
        var player: ExoPlayer? = null
        var error: String? = null
        var selectedLanguage: String? = null
        var audioTracks = "none"

        instrumentation.runOnMainSync {
            val referer = requireNotNull(stream.referer)
            val origin = android.net.Uri.parse(referer).let { "${it.scheme}://${it.host}" }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/137 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mapOf("Referer" to referer, "Origin" to origin))
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build()
            player = exo

            fun inspectTracks() {
                val rows = exo.currentTracks.groups
                    .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                    .flatMap { group ->
                        (0 until group.length).map { index ->
                            val format = group.getTrackFormat(index)
                            Triple(format.language, format.label, group.isTrackSelected(index))
                        }
                    }
                audioTracks = rows.joinToString { "${it.first}/${it.second}/selected=${it.third}" }
                selectedLanguage = rows.firstOrNull { it.third }?.first
                if (exo.playbackState == Player.STATE_READY && selectedLanguage != null) completed.countDown()
            }

            exo.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    applyCategoryAudioPreference(exo, category, "kaa")
                    inspectTracks()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    inspectTracks()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = "${playbackError.errorCodeName}: ${playbackError.message}"
                    completed.countDown()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(stream.url))
            exo.prepare()
        }

        val reachedReady = completed.await(45, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player?.release() }
        assertTrue(
            "KAA $category did not reach Media3 READY; error=$error tracks=$audioTracks",
            reachedReady && error == null,
        )
        return PlaybackProbe(requireNotNull(selectedLanguage), audioTracks)
    }

    private data class PlaybackProbe(val selectedLanguage: String, val tracks: String)
}
