package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miruronative.data.settings.CaptionBackgroundColor
import com.miruronative.data.settings.CaptionEdgeStyle
import com.miruronative.data.settings.CaptionStyle
import com.miruronative.data.settings.CaptionTextColor
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.adaptive.focusHighlight

private const val PREVIEW_SAMPLE = "The quick brown fox jumps"

/** Base sp the preview scales; the real players scale a fraction of the video height instead. */
private const val PREVIEW_BASE_SP = 15f

/**
 * Editor for [CaptionStyle], shared by Settings and both players' in-playback menus so the two
 * entry points can never drift apart.
 */
@Composable
fun CaptionAppearanceEditor(
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    val style by SettingsStore.captionStyle.collectAsState()
    Column(modifier.fillMaxWidth()) {
        CaptionPreview(style)
        CaptionChoiceRow(
            label = "Background opacity",
            options = CaptionStyle.BACKGROUND_OPACITY_STEPS,
            selected = style.backgroundOpacityPercent,
            labelOf = { if (it == 0) "Off" else "$it%" },
            onSelect = SettingsStore::setCaptionBackgroundOpacity,
        )
        CaptionChoiceRow(
            label = "Background color",
            options = CaptionBackgroundColor.entries,
            selected = style.backgroundColor,
            labelOf = CaptionBackgroundColor::label,
            onSelect = SettingsStore::setCaptionBackgroundColor,
        )
        CaptionChoiceRow(
            label = "Text size",
            options = CaptionStyle.TEXT_SCALE_STEPS,
            selected = style.textScalePercent,
            labelOf = { "$it%" },
            onSelect = SettingsStore::setCaptionTextScale,
        )
        CaptionChoiceRow(
            label = "Text weight",
            options = listOf(false, true),
            selected = style.boldText,
            labelOf = { if (it) "Bold" else "Regular" },
            onSelect = SettingsStore::setCaptionBold,
        )
        CaptionChoiceRow(
            label = "Bottom margin",
            options = CaptionStyle.BOTTOM_MARGIN_STEPS,
            selected = style.bottomMarginPercent,
            labelOf = { "$it%" },
            onSelect = SettingsStore::setCaptionBottomMargin,
        )
        CaptionChoiceRow(
            label = "Text color",
            options = CaptionTextColor.entries,
            selected = style.textColor,
            labelOf = CaptionTextColor::label,
            onSelect = SettingsStore::setCaptionTextColor,
        )
        CaptionChoiceRow(
            label = "Edge style",
            options = CaptionEdgeStyle.entries,
            selected = style.edgeStyle,
            labelOf = CaptionEdgeStyle::label,
            onSelect = SettingsStore::setCaptionEdgeStyle,
        )
        if (footnote != null) {
            Text(
                footnote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** [CaptionAppearanceEditor] as a dialog; dialogs take D-pad focus reliably on TV. */
@Composable
fun CaptionAppearanceDialog(
    onDismiss: () -> Unit,
    footnote: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caption appearance") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CaptionAppearanceEditor(footnote = footnote)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = SettingsStore::resetCaptionStyle) { Text("Reset") }
        },
    )
}

@Composable
private fun CaptionPreview(style: CaptionStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // Stands in for video: a flat swatch would hide what a translucent background does.
            .background(Brush.linearGradient(listOf(Color(0xFF3A4E6B), Color(0xFF0E1116))))
            .heightIn(min = 120.dp)
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = (style.bottomMarginPercent * 0.6f).dp,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .background(Color(style.backgroundArgb), RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            val fontSize = (PREVIEW_BASE_SP * style.textScalePercent / 100f).sp
            if (style.edgeStyle == CaptionEdgeStyle.OUTLINE) {
                Text(
                    PREVIEW_SAMPLE,
                    color = Color.Black,
                    fontSize = fontSize,
                    fontWeight = if (style.boldText) FontWeight.Bold else FontWeight.Normal,
                    style = TextStyle(drawStyle = Stroke(width = 5f)),
                )
            }
            Text(
                PREVIEW_SAMPLE,
                color = Color(style.textArgb),
                fontSize = fontSize,
                fontWeight = if (style.boldText) FontWeight.Bold else FontWeight.Normal,
                style = if (style.edgeStyle == CaptionEdgeStyle.DROP_SHADOW) {
                    TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), blurRadius = 4f))
                } else {
                    TextStyle.Default
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> CaptionChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option)) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}
