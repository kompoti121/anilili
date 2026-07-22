package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedWebViewChromeTest {
    @Test
    fun `provider chrome suppression covers supported player families`() {
        listOf(
            ".plyr__controls",
            ".plyr__control--overlaid",
            ".vjs-control-bar",
            ".jw-controlbar",
            ".art-controls",
            ".media-control",
            ".fluid_controls_container",
            ".mejs__controls",
            ".shaka-controls-container",
            ".dplayer-controller",
            ".xgplayer-controls",
            ".fp-controls",
            ".playkit-control-bar",
            ".op-controls",
            "[data-media-controls]",
            "video::-webkit-media-controls",
            "html.anili-video-document button",
            "html.anili-video-document [role=\"button\"]",
            "html.anili-video-document input[type=\"range\"]",
        ).forEach { selector ->
            assertTrue("missing selector $selector", HIDE_PROVIDER_CHROME_JS.contains(selector))
        }
    }

    @Test
    fun `provider chrome suppression survives dynamic player rerenders`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("MutationObserver"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("attributeFilter: ['controls']"))
    }

    @Test
    fun `document start suppression is allowed in nested cross origin frames`() {
        assertEquals(setOf("*"), PROVIDER_CHROME_ORIGIN_RULES)
    }

    @Test
    fun `cross origin frames expose progress and receive native player commands`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("AniliProgress.onVideoAvailable()"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("AniliProgress.onTick("))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("window.__aniliDispatchPlayerCommand"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("contentWindow.postMessage(data, '*')"))
    }

    @Test
    fun `native player commands use the injected all frame dispatcher`() {
        val command = webPlayerCommandJs("seek", 42.5)

        assertTrue(command.contains("__aniliDispatchPlayerCommand('seek',42.5)"))
        assertTrue(command.contains("return false"))
    }
}
