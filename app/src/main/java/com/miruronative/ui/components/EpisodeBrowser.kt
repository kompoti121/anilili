package com.miruronative.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.settings.EpisodeLayout
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.TvNativeTextField
import com.miruronative.ui.adaptive.focusHighlight

/** Episodes per range block. One block is meant to be one comfortable scroll. */
const val EPISODE_BLOCK_SIZE = 100

/**
 * Below this the bar is only noise: a single cour already fits in a short scroll and has nothing
 * worth filtering. Long-runners are the case this whole control exists for.
 */
const val EPISODE_BROWSER_MIN_EPISODES = 12

/** One entry of the range picker: a contiguous slice of the list, labelled by its two ends. */
data class EpisodeBlock(val label: String, val episodes: List<EpisodeItem>)

/**
 * Slice [episodes] into labelled blocks. Chunking is by position rather than by episode number, so
 * gaps, recaps and decimal episodes (13.5) still produce even blocks, and a season that starts at
 * 1051 labels its first block from there instead of pretending it starts at 1.
 */
fun episodeBlocks(
    episodes: List<EpisodeItem>,
    blockSize: Int = EPISODE_BLOCK_SIZE,
): List<EpisodeBlock> = episodes.chunked(blockSize).map { chunk ->
    val first = chunk.first().displayNumber
    val last = chunk.last().displayNumber
    EpisodeBlock(if (first == last) first else "$first – $last", chunk)
}

/**
 * Number prefix first, then title. Numbers lead because this field doubles as "jump to episode":
 * on One Piece, typing 1050 should land on that episode rather than on every title that happens
 * to contain those digits.
 */
fun episodeMatchesQuery(episode: EpisodeItem, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    if (episode.displayNumber.startsWith(trimmed, ignoreCase = true)) return true
    return episode.distinctTitle?.contains(trimmed, ignoreCase = true) == true
}

/**
 * Matches in list order, except that number matches come first. Titles carry stray digits — One
 * Piece names episodes after bounties, so "500" also hits "500,000,000" three hundred episodes
 * away — and the viewer typing a number wants that episode at the top, not buried mid-list.
 */
fun filterEpisodes(episodes: List<EpisodeItem>, query: String): List<EpisodeItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return episodes
    val (byNumber, byTitle) = episodes
        .filter { episodeMatchesQuery(it, trimmed) }
        .partition { it.displayNumber.startsWith(trimmed, ignoreCase = true) }
    return byNumber + byTitle
}

/**
 * Index of the block holding [number], falling back to the first. Keeps a resumed long-runner from
 * opening on episode 1 when the viewer is a thousand episodes in.
 */
fun blockIndexContaining(blocks: List<EpisodeBlock>, number: Double?): Int {
    if (number == null) return 0
    return blocks.indexOfFirst { block -> block.episodes.any { it.number == number } }
        .coerceAtLeast(0)
}

/**
 * The control row above a long episode list: range picker, filter/jump field, layout toggle.
 * Callers decide whether to show it at all (see [EPISODE_BROWSER_MIN_EPISODES]); the range picker
 * drops out on its own when every episode already fits in one block.
 */
@Composable
fun EpisodeBrowserBar(
    blocks: List<EpisodeBlock>,
    selectedBlockIndex: Int,
    onSelectBlock: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    layout: EpisodeLayout,
    onToggleLayout: () -> Unit,
    modifier: Modifier = Modifier,
    // The TV player's episode grid is built around D-pad traversal of compact chips; swapping it
    // for tall rows there would be a downgrade, so that one caller drops the toggle.
    showLayoutToggle: Boolean = true,
    // On TV the D-pad never finds this row by itself — 2D focus search walks straight from the
    // episode list to whatever sits above it, the same way it skips the season selector. Callers
    // hand it a requester and aim their neighbours' up/down at it so the remote lands here.
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (blocks.size > 1) {
            EpisodeBlockPicker(
                blocks = blocks,
                selectedIndex = selectedBlockIndex,
                onSelect = onSelectBlock,
            )
        }
        EpisodeFilterField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f),
        )
        if (showLayoutToggle) EpisodeLayoutToggle(layout = layout, onToggle = onToggleLayout)
    }
}

@Composable
private fun EpisodeBlockPicker(
    blocks: List<EpisodeBlock>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = blocks.getOrNull(selectedIndex) ?: blocks.first()
    Box {
        Row(
            modifier = Modifier
                .focusHighlight(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Choose episode range") { expanded = true }
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            blocks.forEachIndexed { index, block ->
                DropdownMenuItem(
                    text = { Text(block.label) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    trailingIcon = if (index == selectedIndex) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    if (device.isTv) {
        TvNativeTextField(
            value = query,
            onValueChange = onQueryChange,
            hint = "Filter episodes",
            modifier = modifier,
        )
    } else {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth(),
            placeholder = { Text("Filter episodes…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear filter")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )
    }
}

@Composable
private fun EpisodeLayoutToggle(layout: EpisodeLayout, onToggle: () -> Unit) {
    val showsGridNext = layout == EpisodeLayout.LIST
    IconButton(
        onClick = onToggle,
        modifier = Modifier.focusHighlight(RoundedCornerShape(10.dp)),
    ) {
        // The glyph shows the layout the press switches TO, which is how view toggles are read.
        Icon(
            imageVector = if (showsGridNext) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
            contentDescription = if (showsGridNext) "Show as grid" else "Show as list",
        )
    }
}

/**
 * Compact number chip used by the grid layout on every screen. Carries its own D-pad activation:
 * the TV episode grid is the one place a remote lands on hundreds of these in a row, and the
 * default clickable does not fire on select there.
 */
@Composable
fun EpisodeNumberChip(
    episode: EpisodeItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float = 0f,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        episode.filler -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusHighlight(RoundedCornerShape(8.dp))
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            episode.displayNumber,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        )
        if (episode.filler) {
            Text(
                "F",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
            )
        }
        // Watched underline: the selected chip is already fully highlighted, so it skips it.
        if (!selected) {
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
