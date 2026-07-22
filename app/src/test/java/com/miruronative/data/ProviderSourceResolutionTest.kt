package com.miruronative.data

import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSourceResolutionTest {

    @Test
    fun `stalled preferred provider times out and falls through to next source`() = runBlocking {
        val attempts = mutableListOf<String>()
        val timedOut = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("animegg"), candidate("kaa")),
            excludedProviders = emptySet(),
            maxAttempts = 5,
            attemptTimeoutMs = 75L,
            onAttempt = attempts::add,
            onTimeout = timedOut::add,
        ) { candidate ->
            if (candidate.provider == "animegg") awaitCancellation()
            playableSources(candidate.provider)
        }

        assertEquals(listOf("animegg", "kaa"), attempts)
        assertEquals(listOf("animegg"), timedOut)
        assertEquals(setOf("animegg"), result.unavailableProviders)
        assertEquals("kaa", result.resolved?.provider)
        assertTrue(result.resolved?.sources?.streams?.isNotEmpty() == true)
    }

    @Test
    fun `provider failure is marked unavailable and does not stop fallback`() = runBlocking {
        val failures = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("animegg"), candidate("allanime")),
            excludedProviders = emptySet(),
            maxAttempts = 5,
            attemptTimeoutMs = 1_000L,
            onFailure = { provider, _ -> failures += provider },
        ) { candidate ->
            if (candidate.provider == "animegg") throw IOException("host unavailable")
            playableSources(candidate.provider)
        }

        assertEquals(listOf("animegg"), failures)
        assertEquals(setOf("animegg"), result.unavailableProviders)
        assertEquals("allanime", result.resolved?.provider)
    }

    @Test
    fun `excluded providers do not consume the attempt budget`() = runBlocking {
        val attempts = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("animegg"), candidate("kaa"), candidate("allanime")),
            excludedProviders = setOf("animegg"),
            maxAttempts = 1,
            attemptTimeoutMs = 1_000L,
            onAttempt = attempts::add,
        ) { candidate -> playableSources(candidate.provider) }

        assertEquals(listOf("kaa"), attempts)
        assertEquals("kaa", result.resolved?.provider)
    }

    @Test
    fun `parent cancellation is not mistaken for a provider timeout`() = runBlocking {
        var result: ProviderCandidateResolution? = null
        var timeout: TimeoutCancellationException? = null

        try {
            withTimeout(75L) {
                result = resolveProviderCandidates(
                    candidates = listOf(candidate("animegg")),
                    excludedProviders = emptySet(),
                    maxAttempts = 1,
                    attemptTimeoutMs = 5_000L,
                ) { awaitCancellation() }
            }
        } catch (e: TimeoutCancellationException) {
            timeout = e
        }

        assertNull(result)
        assertTrue(timeout != null)
    }

    private fun candidate(provider: String) = ProviderSourceCandidate(provider, "episode-$provider")

    private fun playableSources(provider: String) = SourcesResult(
        streams = listOf(
            StreamItem(
                url = "https://$provider.example/master.m3u8",
                type = "hls",
                quality = "auto",
                audio = "sub",
                referer = null,
                isActive = true,
                width = null,
                height = null,
            ),
        ),
        subtitles = emptyList(),
        skip = null,
        download = null,
    )
}
