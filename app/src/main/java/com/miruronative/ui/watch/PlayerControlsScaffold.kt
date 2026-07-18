package com.miruronative.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The shared touch-player chrome: a center transport cluster (prev / −10s / play-pause / +10s /
 * next) over a bottom bar (seek slider, elapsed/total time, and a caller-supplied icon row). Both
 * the native and embed players render this so they are visually identical; each supplies its own
 * wiring and its own trailing icons (the embed has only a settings gear, the native adds subtitle,
 * cast, and fullscreen).
 */
@Composable
internal fun PlayerControlsScaffold(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
    bottomRightIcons: @Composable RowScope.() -> Unit = {},
) {
    // While the thumb is held, the slider tracks the finger, not the once-a-second position
    // update; the seek is committed on release so playback doesn't lurch through drag samples.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    Box(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlIconButton("Previous episode", Icons.Default.SkipPrevious, enabled = hasPrevious, onClick = onPrevious)
            PlayerControlIconButton("Rewind 10 seconds", Icons.Default.FastRewind, onClick = onRewind)
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            PlayerControlIconButton("Forward 10 seconds", Icons.Default.FastForward, onClick = onForward)
            PlayerControlIconButton("Next episode", Icons.Default.SkipNext, enabled = hasNext, onClick = onNext)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val fraction = scrubFraction
                ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = {
                    if (durationMs > 0L) scrubFraction = it
                    onInteract()
                },
                onValueChangeFinished = {
                    scrubFraction?.let { onSeek((it * durationMs).toLong()) }
                    scrubFraction = null
                },
                enabled = durationMs > 0L,
                colors = whiteSliderColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics { contentDescription = "Seek" },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shownMs = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
                Text(
                    "${formatPlayerTime(shownMs)} / ${formatPlayerTime(durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically, content = bottomRightIcons)
            }
        }
    }
}

@Composable
internal fun PlayerControlIconButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

internal fun formatPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
