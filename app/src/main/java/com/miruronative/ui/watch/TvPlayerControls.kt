package com.miruronative.ui.watch

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.miruronative.ui.adaptive.focusHighlight

internal enum class TvPlayerControl {
    PREVIOUS,
    REWIND,
    PLAY_PAUSE,
    FORWARD,
    NEXT,
    VOLUME_DOWN,
    MUTE,
    VOLUME_UP,
    SETTINGS,
    FULLSCREEN,
}

/** Stable left-to-right remote order; the progress bar is deliberately display-only. */
internal fun tvPlayerControlOrder(
    hasSettings: Boolean,
    hasFullscreen: Boolean,
): List<TvPlayerControl> = buildList {
    add(TvPlayerControl.PREVIOUS)
    add(TvPlayerControl.REWIND)
    add(TvPlayerControl.PLAY_PAUSE)
    add(TvPlayerControl.FORWARD)
    add(TvPlayerControl.NEXT)
    add(TvPlayerControl.VOLUME_DOWN)
    add(TvPlayerControl.MUTE)
    add(TvPlayerControl.VOLUME_UP)
    if (hasSettings) add(TvPlayerControl.SETTINGS)
    if (hasFullscreen) add(TvPlayerControl.FULLSCREEN)
}

internal fun opensTvPlayerControls(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

internal fun opensTvPlayerControls(key: Key): Boolean = key == Key.DirectionLeft ||
    key == Key.DirectionRight ||
    key == Key.DirectionUp ||
    key == Key.DirectionDown ||
    key == Key.DirectionCenter ||
    key == Key.Enter ||
    key == Key.NumPadEnter

@Composable
internal fun TvPlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isMuted: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    playPauseFocusRequester: FocusRequester,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeUp: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatTvPlayerTime(positionMs)} / ${formatTvPlayerTime(durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvControlButton("Previous episode", enabled = hasPrevious, onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                }
                TvControlButton("Rewind 10 seconds", onClick = onRewind) {
                    Icon(Icons.Default.FastRewind, contentDescription = null)
                }
                TvControlButton(
                    label = if (isPlaying) "Pause" else "Play",
                    onClick = onPlayPause,
                    modifier = Modifier.focusRequester(playPauseFocusRequester),
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                }
                TvControlButton("Forward 10 seconds", onClick = onForward) {
                    Icon(Icons.Default.FastForward, contentDescription = null)
                }
                TvControlButton("Next episode", enabled = hasNext, onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                }
                TvControlButton("Volume down", onClick = onVolumeDown) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null)
                }
                TvControlButton(if (isMuted) "Unmute" else "Mute", onClick = onToggleMute) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                    )
                }
                TvControlButton("Volume up", onClick = onVolumeUp) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                }
                onSettings?.let { callback ->
                    TvControlButton("Playback settings", onClick = callback) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                }
                onFullscreen?.let { callback ->
                    TvControlButton("Toggle fullscreen", onClick = callback) {
                        Icon(Icons.Default.Fullscreen, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvControlButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .semantics { contentDescription = label }
            .focusHighlight(RoundedCornerShape(28.dp), focusedScale = 1.12f),
    ) {
        icon()
    }
}

private fun formatTvPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
