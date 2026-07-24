package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import kotlinx.coroutines.launch

private data class ListDestination(val status: String, val label: String)

private val listDestinations = listOf(
    ListDestination("CURRENT", "Watching"),
    ListDestination("REPEATING", "Rewatching"),
    ListDestination("PLANNING", "Plan to watch"),
    ListDestination("PAUSED", "Paused"),
    ListDestination("COMPLETED", "Completed"),
    ListDestination("DROPPED", "Dropped"),
)

/**
 * Long-press menu shared by Home and Library Continue Watching cards.
 *
 * Local removal deliberately leaves the remote list untouched. Moving to a non-watching status
 * updates the active account first, then removes the local row so a failed network call never
 * makes the UI claim that AniList or MAL was changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueWatchingActionsDialog(
    entry: HistoryEntry,
    onDismiss: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val service = remember { AccountService.active }
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val currentStatus = remoteStatuses[entry.anilistId]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var busyStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun moveTo(status: String) {
        if (busyStatus != null) return
        busyStatus = status
        errorMessage = null
        scope.launch {
            runCatching { AppGraph.repository.updateAnimeListStatus(entry.anilistId, status) }
                .onSuccess {
                    LibraryStore.updateRemoteStatus(entry.anilistId, status)
                    if (status != "CURRENT" && status != "REPEATING") {
                        LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = true)
                    }
                    LibraryStore.refreshRemoteLibrary(force = true)
                    onDismiss()
                }
                .onFailure { error ->
                    errorMessage = error.message ?: "Couldn't update ${service?.label ?: "your list"}"
                    busyStatus = null
                }
        }
    }

    val remove = {
        LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = true)
        onDismiss()
    }
    val canDismiss = busyStatus == null

    if (device.isTv) {
        Dialog(onDismissRequest = { if (canDismiss) onDismiss() }) {
            Box(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(20.dp),
            ) {
                ContinueWatchingActionsContent(
                    entry = entry,
                    serviceLabel = service?.label,
                    currentStatus = currentStatus,
                    busyStatus = busyStatus,
                    errorMessage = errorMessage,
                    onMoveTo = ::moveTo,
                    onRemove = remove,
                    onClose = onDismiss,
                )
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (canDismiss) onDismiss() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            ContinueWatchingActionsContent(
                entry = entry,
                serviceLabel = service?.label,
                currentStatus = currentStatus,
                busyStatus = busyStatus,
                errorMessage = errorMessage,
                onMoveTo = ::moveTo,
                onRemove = remove,
                onClose = onDismiss,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ContinueWatchingActionsContent(
    entry: HistoryEntry,
    serviceLabel: String?,
    currentStatus: String?,
    busyStatus: String?,
    errorMessage: String?,
    onMoveTo: (String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Manage Continue Watching",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = serviceLabel?.let { "Move on $it to" }
                    ?: "Sign in to AniList or MyAnimeList to move this title between lists.",
                style = if (serviceLabel != null) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = if (serviceLabel != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (serviceLabel != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listDestinations.chunked(2).forEach { destinations ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        destinations.forEach { destination ->
                            ContinueWatchingDestinationCard(
                                destination = destination,
                                selected = destination.status == currentStatus,
                                busy = destination.status == busyStatus,
                                enabled = busyStatus == null && destination.status != currentStatus,
                                onClick = { onMoveTo(destination.status) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(2 - destinations.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onRemove,
                enabled = busyStatus == null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Remove from Continue Watching")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose, enabled = busyStatus == null) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun ContinueWatchingDestinationCard(
    destination: ListDestination,
    selected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(11.dp)
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            selected -> Icon(
                Icons.Default.Check,
                contentDescription = "Current status",
                tint = textColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected || busy) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
