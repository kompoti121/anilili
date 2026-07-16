package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AniListRateSlotTest {
    @Test
    fun `default degraded quota spaces calls across one minute`() {
        val (start, next) = nextRateSlot(now = 1_000, remaining = 50, reset = 0, nextSlot = 0)
        assertEquals(1_000L, start) // no wait
        assertEquals(3_100L, next)  // 30/min plus a small boundary safety margin

        // A second call arriving at the same instant is pushed to the reserved slot.
        val (start2, _) = nextRateSlot(now = 1_000, remaining = 50, reset = 0, nextSlot = next)
        assertEquals(3_100L, start2)
    }

    @Test
    fun `server advertised normal quota increases pacing safely`() {
        val (_, next) = nextRateSlot(now = 1_000, remaining = 80, reset = 0, nextSlot = 0, limit = 90)
        assertEquals(1_766L, next)
    }

    @Test
    fun `spent budget holds until the window resets`() {
        val (start, _) = nextRateSlot(now = 1_000, remaining = 0, reset = 31_000, nextSlot = 0)
        assertEquals(31_000L, start) // waits ~30s for the reset instead of 429-ing
    }

    @Test
    fun `stale spent budget past the reset proceeds immediately`() {
        val (start, _) = nextRateSlot(now = 100_000, remaining = 0, reset = 31_000, nextSlot = 0)
        assertEquals(100_000L, start)
    }

    @Test
    fun `low budget spreads the remaining calls across the window`() {
        // 4 calls left, 8s until reset -> ~2s between calls.
        val (start, next) = nextRateSlot(now = 1_000, remaining = 4, reset = 9_000, nextSlot = 0)
        assertEquals(1_000L, start)
        assertEquals(3_100L, next)
    }

    @Test
    fun `spreading is capped so a single call never stalls too long`() {
        // 1 call left, 100s window -> would be 100s; capped at the max spacing.
        val (_, next) = nextRateSlot(now = 1_000, remaining = 1, reset = 101_000, nextSlot = 0)
        assertEquals(1_000L + MAX_SPACING_MS_TEST, next)
    }

    @Test
    fun `backoff to a far reset is bounded`() {
        val (start, _) = nextRateSlot(now = 1_000, remaining = 0, reset = 201_000, nextSlot = 0)
        assertEquals(1_000L + MAX_BACKOFF_MS_TEST, start)
    }

    private companion object {
        const val MAX_SPACING_MS_TEST = 3_000L
        const val MAX_BACKOFF_MS_TEST = 60_000L
    }
}
