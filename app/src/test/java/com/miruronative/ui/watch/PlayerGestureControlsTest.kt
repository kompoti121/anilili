package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureControlsTest {
    @Test
    fun doubleTapUsesOuterThirdsForSeekingAndCenterForPlayback() {
        assertEquals(PlayerDoubleTapAction.Rewind, playerDoubleTapAction(x = 10f, width = 300f))
        assertEquals(PlayerDoubleTapAction.TogglePlayback, playerDoubleTapAction(x = 100f, width = 300f))
        assertEquals(PlayerDoubleTapAction.TogglePlayback, playerDoubleTapAction(x = 150f, width = 300f))
        assertEquals(PlayerDoubleTapAction.TogglePlayback, playerDoubleTapAction(x = 200f, width = 300f))
        assertEquals(PlayerDoubleTapAction.Forward, playerDoubleTapAction(x = 290f, width = 300f))
    }

    @Test
    fun doubleTapDefaultsToPlaybackWhenSurfaceHasNoWidth() {
        assertEquals(PlayerDoubleTapAction.TogglePlayback, playerDoubleTapAction(x = 0f, width = 0f))
    }
}
