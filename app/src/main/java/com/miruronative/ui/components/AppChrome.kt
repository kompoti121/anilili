package com.miruronative.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/** Shared visibility signal for screen chrome while the user is actively browsing a list. */
val LocalAppChromeVisible = compositionLocalOf { true }

/**
 * Room the overlaying chrome takes at the bottom of the window. The navigation bar draws over the
 * content rather than displacing it, so scrollable screens have to reserve this themselves or
 * their last rows sit underneath it forever.
 */
val LocalAppChromeBottomInset = compositionLocalOf { 0.dp }

/**
 * Keeps each screen's own top bar in sync with the root navigation bar.
 *
 * The bar keeps its full measured height whatever it is doing and slides out via a layer, so the
 * scaffold padding handed to the screen never changes and nothing below it is re-laid out. Earlier
 * versions animated the measured height to zero, which was smooth but still moved every item on
 * the screen by the bar height each time the chrome came and went.
 *
 * Screens must apply that padding as *scroll* padding (`contentPadding`) rather than layout
 * padding, so the list occupies the whole window and its rows pass under the bar as it retreats.
 * Applying it with `Modifier.padding` instead leaves a dead band where the bar used to be.
 */
@Composable
fun ScrollAwareTopBar(content: @Composable () -> Unit) {
    val visible = LocalAppChromeVisible.current
    val shift by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(220),
        label = "topBarShift",
    )
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val width = placeables.maxOfOrNull { it.width } ?: 0
        layout(width, height) {
            placeables.forEach { placeable ->
                placeable.placeWithLayer(0, 0) {
                    translationY = -height * shift
                }
            }
        }
    }
}
