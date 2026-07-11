package com.miruronative.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// The site is dark-first; we commit to a single dark scheme so the app matches it 1:1.
private val MiruroDarkColors = darkColorScheme(
    primary = MiruroAccent,
    onPrimary = MiruroOnSurface,
    secondary = MiruroAccentVariant,
    background = MiruroBackground,
    onBackground = MiruroOnSurface,
    surface = MiruroSurface,
    onSurface = MiruroOnSurface,
    surfaceVariant = MiruroSurfaceVariant,
    onSurfaceVariant = MiruroOnSurfaceVariant,
    outline = MiruroOutline,
)

@Composable
fun MiruroTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION
    MaterialTheme(
        colorScheme = MiruroDarkColors,
        typography = if (isTv) MiruroTvTypography else MiruroTypography,
        content = content,
    )
}
