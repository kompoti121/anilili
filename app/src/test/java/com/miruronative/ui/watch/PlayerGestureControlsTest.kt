package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureControlsTest {
    @Test
    fun playbackTogglePausesEvenWhilePlaybackIsBuffering() {
        assertEquals(false, playerToggleWillPlay(playWhenReady = true))
        assertEquals(true, playerToggleWillPlay(playWhenReady = false))
    }

    @Test
    fun horizontalSlideSeeksRelativeToDragDistance() {
        assertEquals(
            630_000L,
            playerSlideSeekTarget(
                startPositionMs = 600_000L,
                durationMs = 1_440_000L,
                horizontalDragPx = 250f,
                widthPx = 1_000f,
            ),
        )
        assertEquals(
            570_000L,
            playerSlideSeekTarget(
                startPositionMs = 600_000L,
                durationMs = 1_440_000L,
                horizontalDragPx = -250f,
                widthPx = 1_000f,
            ),
        )
    }

    @Test
    fun horizontalSlideClampsToVideoBounds() {
        assertEquals(0L, playerSlideSeekTarget(10_000L, 60_000L, -1_000f, 1_000f))
        assertEquals(60_000L, playerSlideSeekTarget(50_000L, 60_000L, 1_000f, 1_000f))
    }

    @Test
    fun horizontalSlideWithoutDurationKeepsCurrentPosition() {
        assertEquals(15_000L, playerSlideSeekTarget(15_000L, 0L, 500f, 1_000f))
    }
}
