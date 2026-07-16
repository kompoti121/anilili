package com.miruronative.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStyleTest {

    @Test
    fun `default background is translucent, not media3's opaque black`() {
        val alpha = (CaptionStyle().backgroundArgb ushr 24) and 0xFF
        assertTrue("default background must let the video through", alpha in 1..254)
    }

    @Test
    fun `background opacity maps onto the alpha channel`() {
        val style = CaptionStyle(backgroundColor = CaptionBackgroundColor.BLACK)
        assertEquals(0x00000000, style.copy(backgroundOpacityPercent = 0).backgroundArgb)
        assertEquals(0xFF000000.toInt(), style.copy(backgroundOpacityPercent = 100).backgroundArgb)
        // 50% of 255 truncates to 127, not 128.
        assertEquals(0x7F000000, style.copy(backgroundOpacityPercent = 50).backgroundArgb)
        assertEquals(0x99000000.toInt(), style.copy(backgroundOpacityPercent = 60).backgroundArgb)
    }

    @Test
    fun `background opacity preserves the chosen colour's rgb`() {
        val style = CaptionStyle(
            backgroundColor = CaptionBackgroundColor.NAVY,
            backgroundOpacityPercent = 40,
        )
        assertEquals(CaptionBackgroundColor.NAVY.rgb, style.backgroundArgb and 0xFFFFFF)
    }

    @Test
    fun `text colour is always fully opaque`() {
        CaptionTextColor.entries.forEach { color ->
            val argb = CaptionStyle(textColor = color).textArgb
            assertEquals("alpha for $color", 0xFF, (argb ushr 24) and 0xFF)
            assertEquals("rgb for $color", color.rgb, argb and 0xFFFFFF)
        }
    }

    @Test
    fun `css rgba uses a dot decimal regardless of locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale would otherwise emit rgba(..., 0,60) and void the rule.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val css = CaptionStyle(
                backgroundColor = CaptionBackgroundColor.BLACK,
                backgroundOpacityPercent = 60,
            ).backgroundCssRgba()
            assertEquals("rgba(0, 0, 0, 0.60)", css)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `css hex is six digits`() {
        assertEquals("#ffffff", CaptionStyle(textColor = CaptionTextColor.WHITE).textCssHex())
        assertEquals("#4dd0e1", CaptionStyle(textColor = CaptionTextColor.CYAN).textCssHex())
    }

    @Test
    fun `stored values round-trip and unknown values fall back to the default`() {
        CaptionTextColor.entries.forEach {
            assertEquals(it, CaptionTextColor.fromStored(it.storedValue))
        }
        CaptionBackgroundColor.entries.forEach {
            assertEquals(it, CaptionBackgroundColor.fromStored(it.storedValue))
        }
        CaptionEdgeStyle.entries.forEach {
            assertEquals(it, CaptionEdgeStyle.fromStored(it.storedValue))
        }
        assertEquals(CaptionTextColor.WHITE, CaptionTextColor.fromStored("chartreuse"))
        assertEquals(CaptionBackgroundColor.BLACK, CaptionBackgroundColor.fromStored(null))
        assertEquals(CaptionEdgeStyle.NONE, CaptionEdgeStyle.fromStored(""))
    }

    @Test
    fun `defaults are offered as selectable steps`() {
        assertTrue(
            CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT in CaptionStyle.BACKGROUND_OPACITY_STEPS,
        )
        assertTrue(CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT in CaptionStyle.TEXT_SCALE_STEPS)
    }
}
