package com.miruronative.data.settings

/**
 * Subtitle appearance shared by the native (Media3) and embed (WebView) players.
 *
 * Media3's own fallback is white text on `Color.BLACK` with no alpha — an opaque box across the
 * picture — and it only yields to the device's caption settings when the user has switched those
 * on in Accessibility, which almost nobody does. These defaults keep the familiar white-on-dark
 * look but let the video through, and every field is user-overridable.
 */
data class CaptionStyle(
    val backgroundOpacityPercent: Int = DEFAULT_BACKGROUND_OPACITY_PERCENT,
    val backgroundColor: CaptionBackgroundColor = CaptionBackgroundColor.BLACK,
    val textScalePercent: Int = DEFAULT_TEXT_SCALE_PERCENT,
    val boldText: Boolean = DEFAULT_BOLD_TEXT,
    val bottomMarginPercent: Int = DEFAULT_BOTTOM_MARGIN_PERCENT,
    val textColor: CaptionTextColor = CaptionTextColor.WHITE,
    val edgeStyle: CaptionEdgeStyle = CaptionEdgeStyle.NONE,
) {
    /** Background tint with the chosen opacity folded into the alpha channel. */
    val backgroundArgb: Int get() = argbOf(backgroundColor.rgb, backgroundOpacityPercent)

    val textArgb: Int get() = argbOf(textColor.rgb, opacityPercent = 100)

    /** Media3 expresses the safe-area gap as a fraction of the subtitle view's height. */
    val bottomPaddingFraction: Float get() = bottomMarginPercent.coerceIn(0, 100) / 100f

    /** `rgba(...)` form of [backgroundArgb] for the WebView players' injected stylesheet. */
    fun backgroundCssRgba(): String {
        val rgb = backgroundColor.rgb
        val alpha = backgroundOpacityPercent.coerceIn(0, 100) / 100.0
        return "rgba(${(rgb shr 16) and 0xFF}, ${(rgb shr 8) and 0xFF}, ${rgb and 0xFF}, " +
            "${String.format(java.util.Locale.US, "%.2f", alpha)})"
    }

    fun textCssHex(): String = String.format(java.util.Locale.US, "#%06x", textColor.rgb and 0xFFFFFF)

    companion object {
        const val DEFAULT_BACKGROUND_OPACITY_PERCENT = 60
        const val DEFAULT_TEXT_SCALE_PERCENT = 100
        const val DEFAULT_BOLD_TEXT = true
        const val DEFAULT_BOTTOM_MARGIN_PERCENT = 12

        /**
         * Discrete steps rather than a slider: the player menu has to be drivable by a TV remote,
         * where a Slider is close to unusable.
         */
        val BACKGROUND_OPACITY_STEPS = listOf(0, 25, 40, 60, 80, 100)
        val TEXT_SCALE_STEPS = listOf(75, 90, 100, 115, 130, 150, 200)
        val BOTTOM_MARGIN_STEPS = listOf(4, 8, 12, 16, 20, 25)

        val MIN_TEXT_SCALE_PERCENT = TEXT_SCALE_STEPS.first()
        val MAX_TEXT_SCALE_PERCENT = TEXT_SCALE_STEPS.last()
        val MIN_BOTTOM_MARGIN_PERCENT = BOTTOM_MARGIN_STEPS.first()
        val MAX_BOTTOM_MARGIN_PERCENT = BOTTOM_MARGIN_STEPS.last()

        private fun argbOf(rgb: Int, opacityPercent: Int): Int {
            val alpha = opacityPercent.coerceIn(0, 100) * 255 / 100
            return (alpha shl 24) or (rgb and 0xFFFFFF)
        }
    }
}

enum class CaptionTextColor(val storedValue: String, val label: String, val rgb: Int) {
    WHITE("white", "White", 0xFFFFFF),
    YELLOW("yellow", "Yellow", 0xFFEB3B),
    CYAN("cyan", "Cyan", 0x4DD0E1),
    GREEN("green", "Green", 0x81C784),
    MAGENTA("magenta", "Magenta", 0xFF80AB);

    companion object {
        fun fromStored(value: String?): CaptionTextColor =
            entries.firstOrNull { it.storedValue == value } ?: WHITE
    }
}

enum class CaptionBackgroundColor(val storedValue: String, val label: String, val rgb: Int) {
    BLACK("black", "Black", 0x000000),
    GRAY("gray", "Gray", 0x4A4A4A),
    NAVY("navy", "Navy", 0x102A43),
    WHITE("white", "White", 0xFFFFFF);

    companion object {
        fun fromStored(value: String?): CaptionBackgroundColor =
            entries.firstOrNull { it.storedValue == value } ?: BLACK
    }
}

enum class CaptionEdgeStyle(val storedValue: String, val label: String) {
    NONE("none", "None"),
    OUTLINE("outline", "Outline"),
    DROP_SHADOW("shadow", "Shadow");

    companion object {
        fun fromStored(value: String?): CaptionEdgeStyle =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}
