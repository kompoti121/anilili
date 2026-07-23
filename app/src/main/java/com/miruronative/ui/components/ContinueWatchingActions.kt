package com.miruronative.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
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
@Composable
fun ContinueWatchingActionsDialog(
    entry: HistoryEntry,
    onDismiss: () -> Unit,
) {
    val service = remember { AccountService.active }
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val currentStatus = remoteStatuses[entry.anilistId]
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

    AlertDialog(
        onDismissRequest = { if (busyStatus == null) onDismiss() },
        title = { Text("Manage Continue Watching") },
        text = {
            Column {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (service != null) {
                    Text(
                        "Move on ${service.label} to",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listDestinations.forEach { destination ->
                        TextButton(
                            onClick = { moveTo(destination.status) },
                            enabled = busyStatus == null && destination.status != currentStatus,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            when {
                                busyStatus == destination.status -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                currentStatus == destination.status -> {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                            Text(
                                destination.label,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Text(
                        "Sign in to AniList or MyAnimeList to move this title between lists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = true)
                    onDismiss()
                },
                enabled = busyStatus == null,
            ) {
                Text("Remove from Continue Watching")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = busyStatus == null) {
                Text("Cancel")
            }
        },
    )
}
