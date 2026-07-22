package com.miruronative.ui.watch

import com.miruronative.data.settings.CaptionBackgroundColor
import com.miruronative.data.settings.CaptionEdgeStyle
import com.miruronative.data.settings.CaptionStyle
import com.miruronative.data.settings.CaptionTextColor
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCssTest {

    @Test
    fun `styles native cues and the common embed players' dom captions`() {
        val css = captionCss(CaptionStyle())
        assertTrue("native WebVTT cues", css.contains("::cue {"))
        listOf(".plyr__caption", ".vjs-text-track-cue > div", ".jw-text-track-cue", ".art-subtitle")
            .forEach { assertTrue("selector $it", css.contains(it)) }
    }

    @Test
    fun `every declaration is important so the player's own rules cannot win`() {
        val css = captionCss(
            CaptionStyle(
                backgroundOpacityPercent = 40,
                textColor = CaptionTextColor.YELLOW,
                textScalePercent = 130,
            ),
        )
        // Each declaration ends in `!important;`, so counting them catches an unmarked one.
        val declarations = css.count { it == ';' }
        assertTrue("expected declarations, found $declarations", declarations > 0)
        assertTrue(
            "unmarked declaration in: $css",
            Regex(""":[^;{}]+;""").findAll(css).all { it.value.contains("!important") },
        )
    }

    @Test
    fun `background shorthand precedes background-color so it cannot reset it`() {
        val css = captionCss(CaptionStyle(backgroundColor = CaptionBackgroundColor.NAVY))
        val shorthand = css.indexOf("background:")
        val longhand = css.indexOf("background-color:")
        assertTrue("both present", shorthand >= 0 && longhand >= 0)
        assertTrue("shorthand must come first", shorthand < longhand)
    }

    @Test
    fun `edge style drives text-shadow`() {
        assertTrue(captionCss(CaptionStyle(edgeStyle = CaptionEdgeStyle.NONE)).contains("text-shadow: none"))
        assertTrue(
            captionCss(CaptionStyle(edgeStyle = CaptionEdgeStyle.OUTLINE)).contains("-1px -1px 1px #000"),
        )
        assertTrue(
            captionCss(CaptionStyle(edgeStyle = CaptionEdgeStyle.DROP_SHADOW)).contains("2px 2px 3px"),
        )
    }

    @Test
    fun `weight and bottom margin are emitted for web captions`() {
        val bold = captionCss(CaptionStyle(boldText = true, bottomMarginPercent = 16))
        val regular = captionCss(CaptionStyle(boldText = false, bottomMarginPercent = 4))

        assertTrue(bold.contains("font-weight: 700 !important"))
        assertTrue(bold.contains("bottom: 16% !important"))
        assertTrue(regular.contains("font-weight: 400 !important"))
        assertTrue(regular.contains("bottom: 4% !important"))
    }

    @Test
    fun `css carries no quote or backslash that would break the js string literal`() {
        CaptionTextColor.entries.forEach { text ->
            CaptionBackgroundColor.entries.forEach { background ->
                val css = captionCss(CaptionStyle(textColor = text, backgroundColor = background))
                assertTrue("quote in $css", !css.contains('\'') && !css.contains('\\'))
            }
        }
    }
}
