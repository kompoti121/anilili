package com.miruronative.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultQualityTest {
    @Test
    fun `auto never picks a height`() {
        assertNull(DefaultQuality.AUTO.pickHeight(listOf(1080, 720, 480)))
    }

    @Test
    fun `highest picks the top available height`() {
        assertEquals(1080, DefaultQuality.HIGHEST.pickHeight(listOf(480, 1080, 720)))
        assertNull(DefaultQuality.HIGHEST.pickHeight(emptyList()))
    }

    @Test
    fun `fixed target picks closest height without going over`() {
        assertEquals(720, DefaultQuality.P720.pickHeight(listOf(1080, 720, 480)))
        assertEquals(480, DefaultQuality.P720.pickHeight(listOf(1080, 480)))
    }

    @Test
    fun `fixed target falls back to lowest when everything is above it`() {
        assertEquals(720, DefaultQuality.P480.pickHeight(listOf(1080, 720)))
    }

    @Test
    fun `stored values round-trip and unknown falls back to highest`() {
        DefaultQuality.entries.forEach { quality ->
            assertEquals(quality, DefaultQuality.fromStored(quality.storedValue))
        }
        assertEquals(DefaultQuality.HIGHEST, DefaultQuality.fromStored(null))
        assertEquals(DefaultQuality.HIGHEST, DefaultQuality.fromStored("bogus"))
    }
}
