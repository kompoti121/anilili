package com.miruronative.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test

class FlixcloudBridgeTest {

    @Test
    fun `resolver blocks hidden playback before provider scripts run`() {
        assertTrue(FLIXCLOUD_EARLY_PLAYBACK_GUARD_JS.contains("HTMLMediaElement.prototype"))
        assertTrue(FLIXCLOUD_EARLY_PLAYBACK_GUARD_JS.contains("media.play = function()"))
        assertTrue(FLIXCLOUD_EARLY_PLAYBACK_GUARD_JS.contains("return Promise.resolve()"))
        assertTrue(FLIXCLOUD_EARLY_PLAYBACK_GUARD_JS.contains("video.muted = true"))
        assertTrue(FLIXCLOUD_EARLY_PLAYBACK_GUARD_JS.contains("video.pause()"))
        assertTrue(FLIXCLOUD_EARLY_PLAYBACK_GUARD_JS.contains("MutationObserver"))
    }
}
