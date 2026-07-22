package com.miruronative.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.miruronative.R
import com.miruronative.ui.theme.MiruroAccent
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

const val WaterFillDurationMillis = 5_760

private data class BubbleSpec(
    val spawnOffset: Float,
    val trailOffset: Float,
    val depth: Float,
    val radius: Float,
)

private val BubbleSpecs = listOf(
    BubbleSpec(0.00f, 0.00f, 0.16f, 0.017f),
    BubbleSpec(0.18f, 0.07f, 0.11f, 0.011f),
    BubbleSpec(0.34f, 0.13f, 0.18f, 0.020f),
    BubbleSpec(0.49f, 0.19f, 0.13f, 0.014f),
    BubbleSpec(0.63f, 0.25f, 0.17f, 0.018f),
    BubbleSpec(0.76f, 0.31f, 0.10f, 0.010f),
    BubbleSpec(0.88f, 0.37f, 0.15f, 0.015f),
)

/**
 * The shared app loader: the exact Anilili mark is revealed by a slow, traveling water fill.
 * [playOnce] is used by the launch splash; regular loading states loop the same motion.
 */
@Composable
fun WaterFillLogoIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    playOnce: Boolean = false,
    color: Color = MiruroAccent,
    fillDurationMillis: Int = WaterFillDurationMillis,
) {
    val resources = LocalContext.current.resources
    val logoMask = remember(resources) {
        ImageBitmap.imageResource(resources, R.drawable.anilili_logo_mask)
    }
    val logoOutline = remember(resources) {
        ImageBitmap.imageResource(resources, R.drawable.anilili_logo_outline)
    }

    val oneShotProgress = remember { Animatable(0f) }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(playOnce, fillDurationMillis) {
        if (playOnce) {
            oneShotProgress.snapTo(0f)
            oneShotProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(fillDurationMillis, easing = LinearEasing),
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "waterFillLogo")
    val loopingProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = fillDurationMillis + 1_200
                0f at 0
                1f at fillDurationMillis using LinearEasing
                1f at fillDurationMillis + 600
                0f at fillDurationMillis + 1_200
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "fillProgress",
    )
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "wavePhase",
    )
    val bubblePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_000, easing = LinearEasing)),
        label = "bubblePhase",
    )

    val progress = if (playOnce) oneShotProgress.value else loopingProgress
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Loading" }
            .graphicsLayer {
                alpha = entrance.value
                val scale = 0.9f + entrance.value * 0.1f
                scaleX = scale
                scaleY = scale
            },
    ) {
        val destination = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        val minDimension = this.size.minDimension
        val outlineAlpha = 0.25f

        drawImage(
            image = logoOutline,
            dstOffset = IntOffset.Zero,
            dstSize = destination,
            alpha = outlineAlpha,
            colorFilter = ColorFilter.tint(color),
            filterQuality = FilterQuality.High,
        )

        fun surfaceY(x: Float): Float {
            val base = this.size.height * (1.08f - progress * 1.16f)
            val normalizedX = x / this.size.width.coerceAtLeast(1f)
            val primary = sin((normalizedX * 1.58f - wavePhase) * 2f * PI).toFloat()
            val secondary = sin((normalizedX * 3.25f - wavePhase * 1.36f + 0.7f) * 2f * PI).toFloat()
            return base + minDimension * (0.019f * primary + 0.006f * secondary)
        }

        val water = Path().apply {
            val samples = 48
            moveTo(0f, surfaceY(0f))
            for (sample in 1..samples) {
                val x = this@Canvas.size.width * sample / samples
                lineTo(x, surfaceY(x))
            }
            lineTo(this@Canvas.size.width, this@Canvas.size.height)
            lineTo(0f, this@Canvas.size.height)
            close()
        }
        drawContext.canvas.saveLayer(
            Rect(0f, 0f, this.size.width, this.size.height),
            Paint(),
        )
        drawPath(water, color)

        if (progress > 0.025f) {
            BubbleSpecs.forEach { bubble ->
                val life = (bubblePhase + bubble.spawnOffset) % 1f
                val travel = (wavePhase - bubble.trailOffset + 1f) % 1f
                val x = this.size.width * (0.12f + travel * 0.76f)
                val y = surfaceY(x) + this.size.height * bubble.depth * (1f - life)
                if (y >= surfaceY(x) && y <= this.size.height) {
                    val opacity = sin(PI * life).toFloat().coerceAtLeast(0f) * 0.86f
                    drawCircle(
                        color = Color.Black.copy(alpha = opacity),
                        radius = minDimension * bubble.radius * (0.72f + 0.38f * life),
                        center = androidx.compose.ui.geometry.Offset(x, y),
                        style = Stroke(width = max(1f, minDimension * 0.007f)),
                        blendMode = BlendMode.DstOut,
                    )
                }
            }
        }

        // Apply the transparent logo bitmap as an alpha mask after drawing water and bubbles.
        drawImage(
            image = logoMask,
            dstOffset = IntOffset.Zero,
            dstSize = destination,
            blendMode = BlendMode.DstIn,
            filterQuality = FilterQuality.High,
        )
        drawContext.canvas.restore()
    }
}
