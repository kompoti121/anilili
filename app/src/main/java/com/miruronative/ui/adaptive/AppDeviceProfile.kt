package com.miruronative.ui.adaptive

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme

enum class AppFormFactor { PHONE, TABLET, TV }

data class AppDeviceProfile(
    val formFactor: AppFormFactor,
    val widthDp: Int,
) {
    val isTv: Boolean get() = formFactor == AppFormFactor.TV
    val isTablet: Boolean get() = formFactor == AppFormFactor.TABLET
    val isExpanded: Boolean get() = widthDp >= 840 || isTv
    val useNavigationRail: Boolean get() = widthDp >= 600 || isTv

    val pagePadding: Dp
        get() = when {
            isTv -> 48.dp
            isExpanded -> 32.dp
            isTablet -> 24.dp
            else -> 16.dp
        }

    val posterWidth: Dp
        get() = when {
            isTv -> 168.dp
            isExpanded -> 152.dp
            isTablet -> 140.dp
            else -> 128.dp
        }

    val gridMinWidth: Dp
        get() = when {
            isTv -> 170.dp
            isExpanded -> 150.dp
            isTablet -> 132.dp
            else -> 110.dp
        }

    val episodeColumns: Int
        get() = when {
            isTv -> 8
            widthDp >= 1_000 -> 8
            isExpanded -> 7
            isTablet -> 6
            else -> 5
        }
}

fun resolveAppDeviceProfile(uiModeType: Int, widthDp: Int): AppDeviceProfile {
    val formFactor = when {
        uiModeType == Configuration.UI_MODE_TYPE_TELEVISION -> AppFormFactor.TV
        widthDp >= 600 -> AppFormFactor.TABLET
        else -> AppFormFactor.PHONE
    }
    return AppDeviceProfile(formFactor, widthDp)
}

val LocalAppDeviceProfile = staticCompositionLocalOf {
    AppDeviceProfile(AppFormFactor.PHONE, 360)
}

@Composable
fun rememberAppDeviceProfile(): AppDeviceProfile {
    val configuration = LocalConfiguration.current
    val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return remember(uiModeType, configuration.screenWidthDp) {
        resolveAppDeviceProfile(uiModeType, configuration.screenWidthDp)
    }
}

/** Clear focus treatment for TV remotes, keyboards, and game controllers. */
@Composable
fun Modifier.focusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = if (LocalAppDeviceProfile.current.isTv) 1.06f else 1.025f,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        label = "focused-scale",
    )
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .zIndex(if (focused) 1f else 0f)
        .scale(scale)
        .clip(shape)
        .border(width = if (focused) 3.dp else 0.dp, color = borderColor, shape = shape)
}
