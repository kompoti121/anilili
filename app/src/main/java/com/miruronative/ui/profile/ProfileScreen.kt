package com.miruronative.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.library.HistoryEntry
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.model.MediaListEntry
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.UiState
import com.miruronative.ui.components.SectionHeader
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onAnimeClick: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val device = LocalAppDeviceProfile.current
    val token by AuthManager.token.collectAsState()
    val profile by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val autoSync by SettingsStore.autoSyncAniList.collectAsState()
    val preferDub by SettingsStore.preferDub.collectAsState()
    var showLogin by remember { mutableStateOf(false) }

    LaunchedEffect(token) { vm.loadIfLoggedIn() }

    if (showLogin) {
        LoginWebView(
            onToken = { showLogin = false; vm.onLoggedIn(it) },
            onCancel = { showLogin = false },
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { AniListSection(token != null, profile, onLogin = { showLogin = true }, onLogout = vm::logout) }

            (profile as? UiState.Success)?.data?.let { p ->
                if (p.watching.isNotEmpty()) {
                    item { SectionHeader("Watching on AniList", Modifier.padding(device.pagePadding)) }
                    item { EntryRow(p.watching, onAnimeClick) }
                }
                if (p.planning.isNotEmpty()) {
                    item { SectionHeader("Planning on AniList", Modifier.padding(device.pagePadding)) }
                    item { EntryRow(p.planning, onAnimeClick) }
                }
                if (p.paused.isNotEmpty()) {
                    item { SectionHeader("Paused on AniList", Modifier.padding(device.pagePadding)) }
                    item { EntryRow(p.paused, onAnimeClick) }
                }
                if (p.completed.isNotEmpty()) {
                    item { SectionHeader("Completed on AniList", Modifier.padding(device.pagePadding)) }
                    item { EntryRow(p.completed, onAnimeClick) }
                }
            }

            item { SectionHeader("Continue Watching", Modifier.padding(device.pagePadding)) }
            if (history.isEmpty()) {
                item { EmptyHint("Nothing watched yet") }
            } else {
                item {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = device.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
                    ) {
                        items(history) { entry -> HistoryCard(entry, onResume) }
                    }
                }
            }

            item { SectionHeader("Watchlist", Modifier.padding(device.pagePadding)) }
            if (watchlist.isEmpty()) {
                item { EmptyHint("Tap the heart on any anime to save it") }
            } else {
                items(watchlist.chunked(device.episodeColumns.coerceAtMost(6))) { row ->
                    val columns = device.episodeColumns.coerceAtMost(6)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = device.pagePadding, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { entry -> WatchlistCard(entry, onAnimeClick, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 16.dp)) {
                    HorizontalDivider()
                    SectionHeader("Playback & sync", Modifier.padding(device.pagePadding))
                    SettingSwitch("Autoplay next episode", "Continue automatically when an episode ends", autoplay, SettingsStore::setAutoplay)
                    SettingSwitch("Prefer dubbed audio", "Use dub first when starting a new series", preferDub, SettingsStore::setPreferDub)
                    SettingSwitch("Sync progress to AniList", "Update your AniList episode progress while watching", autoSync, SettingsStore::setAutoSyncAniList)
                    TextButton(
                        onClick = LibraryStore::clearHistory,
                        modifier = Modifier.padding(horizontal = device.pagePadding).focusHighlight(RoundedCornerShape(20.dp)),
                    ) {
                        Text("Clear viewing history")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 4.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.focusProperties { canFocus = false },
        )
    }
}

@Composable
private fun AniListSection(
    loggedIn: Boolean,
    profile: UiState<AniListProfile>?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(device.pagePadding)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        if (!loggedIn) {
            Text("Connect AniList", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sync your watching & planning lists.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Button(
                onClick = onLogin,
                modifier = Modifier.padding(top = 12.dp).focusHighlight(RoundedCornerShape(24.dp)),
            ) {
                Text("Login with AniList")
            }
            return
        }
        when (val p = profile) {
            is UiState.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = p.data.viewer.avatar?.large,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p.data.viewer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val stats = p.data.viewer.statistics?.anime
                        Text(
                            "${stats?.count ?: 0} anime • ${(stats?.minutesWatched ?: 0) / 60}h watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onLogout,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                    ) { Text("Logout") }
                }
            }
            is UiState.Error -> {
                Text("AniList: ${p.message}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                ) { Text("Logout") }
            }
            else -> Text("Loading AniList…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryRow(entries: List<MediaListEntry>, onAnimeClick: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    LazyRow(
        modifier = Modifier.focusGroup(),
        contentPadding = PaddingValues(horizontal = device.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
    ) {
        items(entries) { entry ->
            val media = entry.media ?: return@items
            Column(
                Modifier
                    .width(device.posterWidth)
                    .focusHighlight()
                    .clickable { onAnimeClick(media.id) },
            ) {
                AsyncImage(
                    model = media.coverImage.best,
                    contentDescription = media.title.preferred,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    media.title.preferred,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "EP ${entry.progress}/${media.episodes ?: "?"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onResume: (HistoryEntry) -> Unit) {
    val device = LocalAppDeviceProfile.current
    Column(
        Modifier
            .width(device.posterWidth)
            .focusHighlight()
            .clickable { onResume(entry) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = entry.cover,
                contentDescription = entry.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                contentScale = ContentScale.Crop,
            )
            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.4f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(0.02f)).height(4.dp).background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("EP ${entry.episodeLabel} • ${entry.provider}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WatchlistCard(entry: WatchlistEntry, onAnimeClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.focusHighlight().clickable { onAnimeClick(entry.anilistId) }) {
        AsyncImage(
            model = entry.cover,
            contentDescription = entry.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    val device = LocalAppDeviceProfile.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 8.dp),
    )
}
