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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

private enum class GestureZone { Brightness, Volume }

internal enum class PlayerDoubleTapAction { Rewind, TogglePlayback, Forward }

// Vertical drags are only picked up inside these edge strips, so a swipe across the middle of the
// picture leaves brightness and volume alone. Sizing them by share of the width keeps them within
// thumb reach on a phone held in portrait; the clamp stops them swallowing a landscape screen.
private const val GESTURE_EDGE_FRACTION = 0.28f
private val GESTURE_EDGE_MIN = 64.dp
private val GESTURE_EDGE_MAX = 160.dp

/**
 * Touch-gesture overlay shared by the native and embed players. A vertical drag down the left edge
 * of the screen scrubs brightness (the Activity window's `screenBrightness`); down the right edge it
 * scrubs volume (the media audio stream, which both ExoPlayer and the WebView route through). The
 * middle of the picture is deliberately inert to either, so neither is caught by accident. A
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
    onDoubleTap: ((PlayerDoubleTapAction) -> Unit)? = null,
    onHoldSpeed: ((active: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnHoldSpeed by rememberUpdatedState(onHoldSpeed)

    var level by remember { mutableStateOf<GestureLevel?>(null) }
    var levelTick by remember { mutableIntStateOf(0) }
    var holdSpeedActive by remember { mutableStateOf(false) }
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
                        val edge = (size.width * GESTURE_EDGE_FRACTION)
                            .coerceIn(GESTURE_EDGE_MIN.toPx(), GESTURE_EDGE_MAX.toPx())
                        val zone = when {
                            down.position.x <= edge -> GestureZone.Brightness
                            down.position.x >= size.width - edge -> GestureZone.Volume
                            else -> null
                        }
                        var value = when (zone) {
                            GestureZone.Brightness -> readBrightness(activity)
                            GestureZone.Volume -> readVolume(audioManager)
                            null -> 0f
                        }
                        var dragging = false
                        var holding = false
                        var up: PointerInputChange? = null
                        // The long-press deadline is anchored to the down, not to the last event,
                        // so finger tremor (which streams sub-slop moves) can't keep resetting it.
                        val holdDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis

                        while (true) {
                            val event = if (!dragging && !holding && currentOnHoldSpeed != null) {
                                val remaining = holdDeadline - android.os.SystemClock.uptimeMillis()
                                if (remaining <= 0) {
                                    null
                                } else {
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // Stationary press outlived the long-press window: fast-forward
                                // while held, like YouTube's hold-for-2x.
                                holding = true
                                holdSpeedActive = true
                                currentOnHoldSpeed?.invoke(true)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                up = change
                                break
                            }
                            if (!dragging && !holding) {
                                val dy = change.position.y - down.position.y
                                val dx = change.position.x - down.position.x
                                if (abs(dy) > slop && abs(dy) > abs(dx)) dragging = true
                            }
                            if (dragging) {
                                // A drag started in the middle still counts as a drag — it just
                                // moves nothing — so letting go of one never lands as a tap.
                                if (zone != null) {
                                    val delta = -change.positionChange().y / size.height.toFloat()
                                    value = (value + delta).coerceIn(0f, 1f)
                                    when (zone) {
                                        GestureZone.Brightness -> {
                                            applyBrightness(activity, value)
                                            level = GestureLevel.Brightness(value)
                                        }
                                        GestureZone.Volume -> {
                                            applyVolume(audioManager, value)
                                            level = GestureLevel.Volume(value)
                                        }
                                    }
                                    levelTick++
                                }
                                change.consume()
                            }
                            if (holding) change.consume()
                        }

                        if (holding) {
                            holdSpeedActive = false
                            currentOnHoldSpeed?.invoke(false)
                            up?.consume()
                            return@awaitEachGesture
                        }
                        if (dragging || up == null) return@awaitEachGesture
                        up.consume()
                        if (currentOnDoubleTap == null) {
                            currentOnTap?.invoke()
                            return@awaitEachGesture
                        }
                        // Wait one double-tap window for a second press before committing to a
                        // single tap, so a double-tap doesn't also fire the single-tap action.
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown == null) {
                            currentOnTap?.invoke()
                        } else {
                            secondDown.consume()
                            val action = playerDoubleTapAction(
                                x = secondDown.position.x,
                                width = size.width.toFloat(),
                            )
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == secondDown.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                            }
                            currentOnDoubleTap?.invoke(action)
                        }
                    }
                },
        )

        level?.let { GestureLevelIndicator(it, Modifier.align(Alignment.Center)) }
        if (holdSpeedActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "2x",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = "Playing at double speed",
                    tint = Color.White,
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
        }
    }
}

/** Outer thirds retain seek gestures; the center third toggles playback. */
internal fun playerDoubleTapAction(x: Float, width: Float): PlayerDoubleTapAction {
    if (width <= 0f) return PlayerDoubleTapAction.TogglePlayback
    val fraction = (x / width).coerceIn(0f, 1f)
    return when {
        fraction < 1f / 3f -> PlayerDoubleTapAction.Rewind
        fraction > 2f / 3f -> PlayerDoubleTapAction.Forward
        else -> PlayerDoubleTapAction.TogglePlayback
    }
}

@Composable
internal fun PlaybackGestureIndicator(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(76.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(38.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (isPlaying) "Playing" else "Paused",
            tint = Color.White,
            modifier = Modifier.size(38.dp),
        )
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
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Volume" }
                // Material3's Slider ignores D-pad keys, so on TV the volume could never be
                // changed here. Left/right nudge by 5%; at the ends the event is released so
                // focus can still escape (left at 0% reaches the mute button).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionLeft -> -0.05f
                        Key.DirectionRight -> +0.05f
                        else -> return@onPreviewKeyEvent false
                    }
                    val next = (volume + delta).coerceIn(0f, 1f)
                    if (next == volume) {
                        false
                    } else {
                        lastInteractMs = System.currentTimeMillis()
                        volume = next
                        applyVolume(audioManager, next)
                        onInteract()
                        true
                    }
                },
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
