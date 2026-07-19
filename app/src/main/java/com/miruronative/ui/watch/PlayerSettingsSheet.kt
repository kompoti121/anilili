package com.miruronative.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal enum class PlayerContentScale(val label: String) {
    FIT("Fit"),
    CROP("Crop"),
    FILL("Fill"),
}

internal data class PlayerQualityOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

private val SheetColor = Color(0xFF1B1B1F)

/**
 * The player's settings, as a bottom sheet styled to match the shared player chrome: volume,
 * playback speed, quality, and playback toggles. Every section is capability-gated — pass `null`
 * or an empty list and it isn't drawn — so the same sheet serves the native and embed players
 * without either showing a control it can't honor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSettingsSheet(
    onDismiss: () -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    speed: Float? = null,
    onSpeedChange: (Float) -> Unit = {},
    qualityOptions: List<PlayerQualityOption> = emptyList(),
    subtitleOptions: List<PlayerQualityOption> = emptyList(),
    audioOptions: List<PlayerQualityOption> = emptyList(),
    contentScale: PlayerContentScale? = null,
    onContentScaleChange: (PlayerContentScale) -> Unit = {},
    onCaptionAppearance: (() -> Unit)? = null,
    autoSkip: Boolean? = null,
    onAutoSkipChange: (Boolean) -> Unit = {},
    onEnterPip: (() -> Unit)? = null,
) {
    val sections: @Composable () -> Unit = {
        SheetSections(
            autoplay = autoplay,
            onAutoplayChange = onAutoplayChange,
            speed = speed,
            onSpeedChange = onSpeedChange,
            qualityOptions = qualityOptions,
            subtitleOptions = subtitleOptions,
            audioOptions = audioOptions,
            contentScale = contentScale,
            onContentScaleChange = onContentScaleChange,
            onCaptionAppearance = onCaptionAppearance,
            autoSkip = autoSkip,
            onAutoSkipChange = onAutoSkipChange,
            onEnterPip = onEnterPip,
        )
    }
    // A ModalBottomSheet opens a second window, which the TV D-pad and TalkBack focus never
    // reliably enter — the remote keeps driving the player underneath. TV gets the same
    // sections as an in-window side panel instead, where standard Compose focus applies.
    if (LocalAppDeviceProfile.current.isTv) {
        TvSettingsPanel(onDismiss, sections)
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColor,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 32.dp),
        ) {
            Text(
                "Settings",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            sections()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetSections(
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    speed: Float?,
    onSpeedChange: (Float) -> Unit,
    qualityOptions: List<PlayerQualityOption>,
    subtitleOptions: List<PlayerQualityOption>,
    audioOptions: List<PlayerQualityOption>,
    contentScale: PlayerContentScale?,
    onContentScaleChange: (PlayerContentScale) -> Unit,
    onCaptionAppearance: (() -> Unit)?,
    autoSkip: Boolean?,
    onAutoSkipChange: (Boolean) -> Unit,
    onEnterPip: (() -> Unit)?,
) {
    SectionLabel("Volume")
    MediaVolumeSlider(modifier = Modifier.fillMaxWidth(), showPercentLabel = true)

    speed?.let { current ->
        SectionLabel("Playback Speed")
        SpeedSlider(current, onSpeedChange)
    }

    if (qualityOptions.isNotEmpty()) {
        SectionLabel("Quality")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            qualityOptions.forEach { option ->
                ChoiceChip(option.label, option.selected, option.onSelect)
            }
        }
    }

    if (audioOptions.size > 1) {
        SectionLabel("Audio")
        audioOptions.forEach { option ->
            TrackRow(option.label, option.selected, option.onSelect)
        }
    }

    if (subtitleOptions.isNotEmpty()) {
        SectionLabel("Subtitles")
        subtitleOptions.forEach { option ->
            TrackRow(option.label, option.selected, option.onSelect)
        }
    }

    contentScale?.let { current ->
        SectionLabel("Content Scale")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerContentScale.entries.forEach { scale ->
                ChoiceChip(scale.label, scale == current) { onContentScaleChange(scale) }
            }
        }
    }

    onCaptionAppearance?.let { open ->
        SectionLabel("Captions")
        ClickableRow("Caption appearance…", open)
    }

    SectionLabel("Playback")
    ToggleRow("Auto-play next episode", autoplay, onAutoplayChange)
    autoSkip?.let { ToggleRow("Auto-skip intro/outro", it, onAutoSkipChange) }
    onEnterPip?.let { ClickableRow("Picture-in-Picture", it) }
}

/**
 * TV presentation of the player settings: an in-window right-side panel. Being in the player's
 * own window (unlike a ModalBottomSheet) means the D-pad and TalkBack traverse it like any other
 * Compose content. Focus is trapped inside while it is open; Back or the close button dismiss.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvSettingsPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Let the panel attach before grabbing focus from the player controls behind it.
        delay(64)
        runCatching { initialFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(420.dp)
                .background(SheetColor)
                // Keep the remote inside the panel; Back and the close button leave it.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .semantics { paneTitle = "Player settings" }
                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 32.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(initialFocus)
                        .focusHighlight(RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close settings", tint = Color.White)
                }
            }
            content()
        }
    }
}

@Composable
private fun SpeedSlider(speed: Float, onSpeedChange: (Float) -> Unit) {
    val speeds = PlaybackSpeeds
    val index = remember(speed) {
        speeds.indexOfFirst { abs(it - speed) < 0.001f }
            .takeIf { it >= 0 }
            ?: speeds.indexOfFirst { it == 1f }.coerceAtLeast(0)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Speed, contentDescription = null, tint = Color.White)
        Slider(
            value = index.toFloat(),
            onValueChange = { onSpeedChange(speeds[it.roundToInt().coerceIn(0, speeds.lastIndex)]) },
            valueRange = 0f..speeds.lastIndex.toFloat(),
            steps = (speeds.size - 2).coerceAtLeast(0),
            colors = whiteSliderColors(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "Playback speed" }
                // Material3's Slider ignores D-pad keys, so on TV the value could never be
                // changed. Left/right step through the speed list; at the ends the event is
                // released so focus can still escape.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val next = when (event.key) {
                        Key.DirectionLeft -> index - 1
                        Key.DirectionRight -> index + 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (next in speeds.indices) {
                        onSpeedChange(speeds[next])
                        true
                    } else {
                        false
                    }
                },
        )
        Text(
            speed.formatPlaybackSpeed(),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 40.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .focusHighlight(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
            )
            // Radio semantics so TalkBack announces "selected" and reads it as a choice.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(8.dp))
            // Radio semantics carry the selection state, so the check icon is decorative.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(8.dp))
            // One toggleable row (the inner Switch is display-only) so TalkBack reads
            // "<label>, switch, on/off" as a single stop instead of two half-described ones.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ClickableRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun whiteSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
)
