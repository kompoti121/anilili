package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProviderParsersTest {
    @Test
    fun parsesNativeEpisodeIds() {
        val request = NativeProviderParsers.episodeRequest("watch/animegg/16498/dub/animegg-7")

        assertEquals("animegg", request?.provider)
        assertEquals(16498, request?.anilistId)
        assertEquals("dub", request?.audio)
        assertEquals(7, request?.episode)
        assertNull(NativeProviderParsers.episodeRequest("https://example.com/not-an-episode"))
    }

    @Test
    fun extractsAttributesAndDecodesEntities() {
        val tag = "<track src=\"https://cdn.test/sub.vtt?a=1&amp;b=2\" label='English'>"

        assertEquals("https://cdn.test/sub.vtt?a=1&b=2", NativeProviderParsers.attr(tag, "src"))
        assertEquals("English", NativeProviderParsers.attr(tag, "label"))
        assertEquals("Attack & Titan", NativeProviderParsers.stripTags("<b>Attack</b> &amp; Titan"))
    }

    @Test
    fun titleMatchingAndHlsDiscoveryHandleProviderMarkup() {
        assertEquals(1.0, NativeProviderParsers.titleScore("Attack on Titan", "Attack on Titan"), 0.0)
        assertTrue(NativeProviderParsers.titleScore("Re:Zero", "ReZero Season 2") > 0.8)
        assertEquals(
            listOf("https://cdn.test/master.m3u8?token=abc"),
            NativeProviderParsers.hlsUrls("file: \"https:\\/\\/cdn.test\\/master.m3u8?token=abc\""),
        )
    }
}
