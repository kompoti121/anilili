package com.miruronative.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface GestureLevel {
    val fraction: Float

    data class Brightness(override val fraction: Float) : GestureLevel
    data class Volume(override val fraction: Float) : GestureLevel
}

/**
 * Touch-gesture overlay shared by the native and embed players. A vertical drag on the left half
 * of the screen scrubs brightness (the Activity window's `screenBrightness`); on the right half it
 * scrubs volume (the media audio stream, which both ExoPlayer and the WebView route through). A
 * transient slider indicator shows the level while dragging.
 *
 * Taps and double-taps are handled here too so a single pointer handler owns the surface: the drag
 * detector and the tap detectors can't be separate `pointerInput`s without one consuming the down
 * the other needs. The down is always consumed, so the player/page beneath never sees the touch.
 */
@Composable
internal fun PlayerGestureControls(
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onDoubleTap: ((isRightHalf: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    var level by remember { mutableStateOf<GestureLevel?>(null) }
    var levelTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(levelTick) {
        if (level != null) {
            delay(700)
            level = null
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(activity, audioManager) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val leftHalf = down.position.x < size.width / 2f
                        var value = if (leftHalf) {
                            readBrightness(activity)
                        } else {
                            readVolume(audioManager)
                        }
                        var dragging = false
                        var up: PointerInputChange? = null

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                up = change
                                break
                            }
                            if (!dragging) {
                                val dy = change.position.y - down.position.y
                                val dx = change.position.x - down.position.x
                                if (abs(dy) > slop && abs(dy) > abs(dx)) dragging = true
                            }
                            if (dragging) {
                                val delta = -change.positionChange().y / size.height.toFloat()
                                value = (value + delta).coerceIn(0f, 1f)
                                if (leftHalf) {
                                    applyBrightness(activity, value)
                                    level = GestureLevel.Brightness(value)
                                } else {
                                    applyVolume(audioManager, value)
                                    level = GestureLevel.Volume(value)
                                }
                                levelTick++
                                change.consume()
                            }
                        }

                        if (dragging || up == null) return@awaitEachGesture
                        up.consume()
                        if (onDoubleTap == null) {
                            onTap?.invoke()
                            return@awaitEachGesture
                        }
                        // Wait one double-tap window for a second press before committing to a
                        // single tap, so a double-tap doesn't also fire the single-tap action.
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown == null) {
                            onTap?.invoke()
                        } else {
                            secondDown.consume()
                            onDoubleTap(secondDown.position.x >= size.width / 2f)
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == secondDown.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                            }
                        }
                    }
                },
        )

        level?.let { GestureLevelIndicator(it, Modifier.align(Alignment.Center)) }
    }
}

@Composable
private fun GestureLevelIndicator(level: GestureLevel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = when (level) {
            is GestureLevel.Brightness -> Icons.Default.BrightnessHigh
            is GestureLevel.Volume ->
                if (level.fraction <= 0.001f) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .width(6.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level.fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${(level.fraction * 100).roundToInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Clears any brightness override this overlay set, restoring the system brightness. Call when the
 * player leaves the screen so a dim setting chosen for a night episode doesn't dim the whole app.
 */
internal fun resetPlayerBrightness(context: Context) {
    val window = context.findActivity()?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

/**
 * A visible volume control — mute-toggle icon plus a draggable slider — for the player control
 * bars, so volume is discoverable rather than only reachable through the edge-drag gesture. Drives
 * the media audio stream (shared by ExoPlayer and the WebView) and polls it so the thumb tracks
 * the hardware volume keys, except while the user is actively dragging.
 *
 * The [modifier] sizes the row; the slider takes the remaining width. [onInteract] fires on every
 * change so the caller can keep its controls from auto-hiding mid-adjustment.
 */
@Composable
internal fun MediaVolumeSlider(
    modifier: Modifier = Modifier,
    showPercentLabel: Boolean = false,
    onInteract: () -> Unit = {},
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var volume by remember { mutableStateOf(readVolume(audioManager)) }
    var lastAudible by remember { mutableStateOf(volume.takeIf { it > 0f } ?: 0.5f) }
    var lastInteractMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(audioManager) {
        while (true) {
            delay(500)
            if (System.currentTimeMillis() - lastInteractMs > 700) {
                volume = readVolume(audioManager)
            }
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                lastInteractMs = System.currentTimeMillis()
                if (volume > 0.001f) {
                    lastAudible = volume
                    applyVolume(audioManager, 0f)
                    volume = 0f
                } else {
                    val target = lastAudible.coerceAtLeast(0.1f)
                    applyVolume(audioManager, target)
                    volume = target
                }
                onInteract()
            },
        ) {
            Icon(volumeIcon(volume), contentDescription = if (volume > 0.001f) "Mute" else "Unmute", tint = Color.White)
        }
        Slider(
            value = volume,
            onValueChange = {
                lastInteractMs = System.currentTimeMillis()
                volume = it
                applyVolume(audioManager, it)
                onInteract()
            },
            colors = whiteSliderColors(),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Volume" },
        )
        if (showPercentLabel) {
            Text(
                "${(volume * 100).roundToInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 10.dp).widthIn(min = 44.dp),
            )
        }
    }
}

private fun volumeIcon(fraction: Float): ImageVector = when {
    fraction <= 0.001f -> Icons.AutoMirrored.Filled.VolumeOff
    fraction < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun readBrightness(activity: Activity?): Float {
    val attr = activity?.window?.attributes?.screenBrightness ?: return 0.5f
    if (attr >= 0f) return attr.coerceIn(0f, 1f)
    // No window override set yet: seed from the system brightness so the first drag continues
    // from what the user currently sees rather than jumping.
    val system = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull() ?: 128
    return (system / 255f).coerceIn(0f, 1f)
}

private fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    // Floor above zero so the screen never goes fully black and strand the user in the dark.
    window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.02f, 1f) }
}

private fun readVolume(audioManager: AudioManager?): Float {
    audioManager ?: return 0.5f
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0.5f
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyVolume(audioManager: AudioManager?, value: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        (value * max).roundToInt().coerceIn(0, max),
        0,
    )
}
