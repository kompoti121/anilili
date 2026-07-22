package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miruronative.ui.adaptive.focusHighlight

@Composable
fun LoadingBox(modifier: Modifier = Modifier, message: String? = null) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WaterFillLogoIndicator(size = 88.dp)
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                )
            }
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 16.dp).focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * Thin watched-progress bar for episode rows/chips: full for episodes already passed, partial
 * for the one in progress. Draws nothing when there is no progress to show.
 */
@Composable
fun WatchProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    if (fraction <= 0.01f) return
    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * How much of [episodeNumber] counts as watched, judged from the anime's single history entry:
 * everything before the episode being tracked is complete, the tracked episode carries its
 * playback fraction, and later episodes are untouched.
 */
fun episodeWatchFraction(entry: com.miruronative.data.library.HistoryEntry?, episodeNumber: Double): Float = when {
    entry == null -> 0f
    episodeNumber < entry.episodeNumber -> 1f
    episodeNumber == entry.episodeNumber -> entry.progressFraction
    else -> 0f
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            ) { Text(actionLabel) }
        }
    }
}
