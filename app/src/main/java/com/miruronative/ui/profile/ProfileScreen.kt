package com.miruronative.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
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
import com.miruronative.data.reminder.AutomaticReleaseManager
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.UiState
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.components.PullRefreshContainer
import com.miruronative.ui.components.RatingBadge
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LibraryView(val label: String) {
    WATCHLIST("My watchlist"),
    WATCHING("Watching"),
    REWATCHING("Re-watching"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    DROPPED("Dropped"),
}

private data class SelectOption(val value: String?, val label: String)

private data class SavedAnimeCardData(
    val id: Int,
    val title: String,
    val cover: String?,
    val format: String?,
    val airingStatus: String?,
    val status: String?,
    val userScore: Double?,
    val averageScore: Int?,
    val progress: Int?,
    val totalEpisodes: Int?,
)

private val formatOptions = listOf(
    SelectOption(null, "Any format"),
    SelectOption("TV", "TV"),
    SelectOption("MOVIE", "Movie"),
    SelectOption("ONA", "ONA"),
    SelectOption("OVA", "OVA"),
    SelectOption("SPECIAL", "Special"),
)

private val airingOptions = listOf(
    SelectOption(null, "Any airing status"),
    SelectOption("RELEASING", "Ongoing"),
    SelectOption("FINISHED", "Completed"),
    SelectOption("NOT_YET_RELEASED", "Upcoming"),
    SelectOption("HIATUS", "On hiatus"),
    SelectOption("CANCELLED", "Cancelled"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onAnimeClick: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val token by AuthManager.token.collectAsState()
    val profileState by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val autoSync by SettingsStore.autoSyncAniList.collectAsState()
    val preferDub by SettingsStore.preferDub.collectAsState()
    val releaseNotifications by SettingsStore.releaseNotifications.collectAsState()
    val syncSavedToAniList by SettingsStore.syncSavedToAniList.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    var selectedViewName by rememberSaveable { mutableStateOf(LibraryView.WATCHLIST.name) }
    var selectedFormat by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAiring by rememberSaveable { mutableStateOf<String?>(null) }
    var titleFilter by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(token) {
        if (token == null) selectedViewName = LibraryView.WATCHLIST.name
        vm.loadIfLoggedIn()
    }

    if (showLogin) {
        LoginWebView(
            onToken = { showLogin = false; vm.onLoggedIn(it) },
            onCancel = { showLogin = false },
        )
        return
    }

    val profile = (profileState as? UiState.Success)?.data
    val combinedWatchlist = remember(profile, watchlist, history) {
        buildCombinedWatchlist(profile, watchlist, history)
    }
    val selectedView = LibraryView.valueOf(selectedViewName)
    val selectedCards = remember(profile, combinedWatchlist, selectedView, selectedFormat, selectedAiring, titleFilter) {
        val source = when (selectedView) {
            LibraryView.WATCHLIST -> combinedWatchlist
            LibraryView.WATCHING -> aniListCards(profile?.watching.orEmpty())
            LibraryView.REWATCHING -> aniListCards(profile?.rewatching.orEmpty())
            LibraryView.PAUSED -> aniListCards(profile?.paused.orEmpty())
            LibraryView.COMPLETED -> aniListCards(profile?.completed.orEmpty())
            LibraryView.DROPPED -> aniListCards(profile?.dropped.orEmpty())
        }
        source.filter { entry ->
            (selectedFormat == null || entry.format == selectedFormat) &&
                (selectedAiring == null || entry.airingStatus == selectedAiring) &&
                (titleFilter.isBlank() || entry.title.contains(titleFilter, ignoreCase = true))
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        if (granted) ReleaseSyncScheduler.runNow(context)
    }

    fun setReleaseNotifications(enabled: Boolean) {
        if (!enabled) {
            SettingsStore.setReleaseNotifications(false)
            AutomaticReleaseManager.cancelAll()
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SettingsStore.setReleaseNotifications(true)
            ReleaseSyncScheduler.runNow(context)
        }
    }

    fun setSavedSync(enabled: Boolean) {
        SettingsStore.setSyncSavedToAniList(enabled)
        if (enabled) LibraryStore.syncSavedToAniList()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Library", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        PullRefreshContainer(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            item {
                ProfileHero(
                    loggedIn = token != null,
                    state = profileState,
                    onLogin = { showLogin = true },
                    onSync = { vm.loadIfLoggedIn() },
                    onLogout = vm::logout,
                )
            }

            profile?.let { loaded ->
                item { ProfileStats(loaded) }
            }

            item {
                LibraryFilters(
                    selectedView = selectedView,
                    onViewChange = { selectedViewName = it.name },
                    selectedFormat = selectedFormat,
                    onFormatChange = { selectedFormat = it },
                    selectedAiring = selectedAiring,
                    onAiringChange = { selectedAiring = it },
                    titleFilter = titleFilter,
                    onTitleFilterChange = { titleFilter = it },
                    resultCount = selectedCards.size,
                    showAniListLists = profile != null,
                )
            }
            item {
                ProfileSectionTitle(
                    selectedView.label,
                    if (selectedView == LibraryView.WATCHLIST) "Saved here and in AniList Planning" else "Synced from AniList",
                )
            }
            if (selectedCards.isEmpty()) {
                item {
                    EmptyPanel(
                        if (selectedView == LibraryView.WATCHLIST && selectedFormat == null && selectedAiring == null && titleFilter.isBlank()) {
                            "Tap the heart on any anime to save it"
                        } else {
                            "No anime match these filters"
                        },
                    )
                }
            } else {
                item {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = device.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
                    ) {
                        items(selectedCards, key = { it.id }) { entry ->
                            SavedAnimeCard(entry, onAnimeClick)
                        }
                    }
                }
            }

            item { ProfileSectionTitle("Continue Watching", "Pick up exactly where you left off") }
            if (history.isEmpty()) {
                item { EmptyPanel("Nothing watched yet") }
            } else {
                item {
                    LazyRow(
                        modifier = Modifier.focusGroup(),
                        contentPadding = PaddingValues(horizontal = device.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
                    ) {
                        items(history, key = { it.anilistId }) { entry -> HistoryCard(entry, onResume) }
                    }
                }
            }

            item {
                SettingsPanel(
                    autoplay = autoplay,
                    preferDub = preferDub,
                    autoSync = autoSync,
                    releaseNotifications = releaseNotifications,
                    syncSavedToAniList = syncSavedToAniList,
                    onReleaseNotificationsChange = ::setReleaseNotifications,
                    onSavedSyncChange = ::setSavedSync,
                )
            }
            }
        }
    }
}

@Composable
private fun ProfileHero(
    loggedIn: Boolean,
    state: UiState<AniListProfile>?,
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        if (!loggedIn) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Your anime, in one place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(
                    "Connect AniList to browse every list, score, and episode progress from Anilili.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Button(onClick = onLogin, modifier = Modifier.padding(top = 16.dp).focusHighlight(RoundedCornerShape(9.dp))) {
                    Text("Login with AniList", fontWeight = FontWeight.Bold)
                }
            }
            return
        }

        when (state) {
            is UiState.Success -> {
                val viewer = state.data.viewer
                Box(Modifier.fillMaxWidth().height(if (device.isTv) 230.dp else 180.dp)) {
                    AsyncImage(
                        model = viewer.bannerImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    )
                    AsyncImage(
                        model = viewer.avatar?.large,
                        contentDescription = viewer.name,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .size(if (device.isTv) 104.dp else 88.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(viewer.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(
                            listOfNotNull("Signed in via AniList", joinedLabel(viewer.createdAt)).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSync, modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp))) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync AniList")
                    }
                    IconButton(onClick = onLogout, modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp))) {
                        Icon(Icons.Default.Close, contentDescription = "Logout")
                    }
                }
            }
            is UiState.Error -> Column(Modifier.padding(18.dp)) {
                Text("AniList could not be loaded", fontWeight = FontWeight.Bold)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onSync) { Text("Try again") }
                    TextButton(onClick = onLogout) { Text("Logout") }
                }
            }
            else -> Text("Syncing your AniList profile…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun ProfileStats(profile: AniListProfile) {
    val device = LocalAppDeviceProfile.current
    val stats = profile.viewer.statistics?.anime
    val days = (stats?.minutesWatched ?: 0L) / 1440.0
    Panel(Modifier.padding(horizontal = device.pagePadding)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            ProfileStat((stats?.count ?: 0).toString(), "Total anime", Modifier.weight(1f))
            ProfileStat(String.format(Locale.US, "%.1f", days), "Days watched", Modifier.weight(1f))
            ProfileStat(String.format(Locale.US, "%.1f", stats?.meanScore ?: 0.0), "Average score", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryFilters(
    selectedView: LibraryView,
    onViewChange: (LibraryView) -> Unit,
    selectedFormat: String?,
    onFormatChange: (String?) -> Unit,
    selectedAiring: String?,
    onAiringChange: (String?) -> Unit,
    titleFilter: String,
    onTitleFilterChange: (String) -> Unit,
    resultCount: Int,
    showAniListLists: Boolean,
) {
    val device = LocalAppDeviceProfile.current
    val viewOptions = (if (showAniListLists) LibraryView.entries else listOf(LibraryView.WATCHLIST))
        .map { SelectOption(it.name, it.label) }
    Panel(Modifier.padding(horizontal = device.pagePadding)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectorField(
                value = selectedView.label,
                options = viewOptions,
                onSelect = { value -> LibraryView.entries.firstOrNull { it.name == value }?.let(onViewChange) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectorField(
                    value = formatOptions.first { it.value == selectedFormat }.label,
                    options = formatOptions,
                    onSelect = onFormatChange,
                    modifier = Modifier.weight(1f),
                )
                SelectorField(
                    value = airingOptions.first { it.value == selectedAiring }.label,
                    options = airingOptions,
                    onSelect = onAiringChange,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = titleFilter,
                onValueChange = onTitleFilterChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Filter by title") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (titleFilter.isNotEmpty()) {
                    { IconButton(onClick = { onTitleFilterChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear title filter") } }
                } else null,
                shape = RoundedCornerShape(9.dp),
                singleLine = true,
            )
            Text(
                "$resultCount anime",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectorField(
    value: String,
    options: List<SelectOption>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(9.dp)).clickable { expanded = true },
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { expanded = false; onSelect(option.value) },
                )
            }
        }
    }
}

@Composable
private fun ProfileSectionTitle(title: String, subtitle: String) {
    val device = LocalAppDeviceProfile.current
    Column(Modifier.padding(horizontal = device.pagePadding, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onResume: (HistoryEntry) -> Unit) {
    val device = LocalAppDeviceProfile.current
    Column(Modifier.width(device.posterWidth).focusHighlight().clickable { onResume(entry) }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .4f))) {
                Box(Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(.02f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        Text("EP ${entry.episodeLabel}  ·  ${entry.provider}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SavedAnimeCard(entry: SavedAnimeCardData, onAnimeClick: (Int) -> Unit) {
    val device = LocalAppDeviceProfile.current
    Column(Modifier.width(device.posterWidth).focusHighlight().clickable { onAnimeClick(entry.id) }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            when {
                entry.userScore != null -> CornerBadge(
                    text = String.format(Locale.US, "★ %.1f", entry.userScore),
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                )
                entry.averageScore != null -> RatingBadge(entry.averageScore, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            entry.progress?.let { progress ->
                CornerBadge(
                    text = "$progress/${entry.totalEpisodes ?: "?"}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                )
            }
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            listOfNotNull(entry.status?.toDisplayLabel(), entry.format?.replace('_', ' ')).joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = Color.Black.copy(alpha = .82f),
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SettingsPanel(
    autoplay: Boolean,
    preferDub: Boolean,
    autoSync: Boolean,
    releaseNotifications: Boolean,
    syncSavedToAniList: Boolean,
    onReleaseNotificationsChange: (Boolean) -> Unit,
    onSavedSyncChange: (Boolean) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    Panel(Modifier.padding(horizontal = device.pagePadding, vertical = 8.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Playback & sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            SettingSwitch("Autoplay next episode", "Continue automatically", autoplay, SettingsStore::setAutoplay)
            SettingSwitch("Prefer dubbed audio", "Use dub first when available", preferDub, SettingsStore::setPreferDub)
            SettingSwitch("Sync progress to AniList", "Update episode progress while watching", autoSync, SettingsStore::setAutoSyncAniList)
            SettingSwitch(
                "Sync saved anime to AniList",
                "New saves are added to Planning without replacing active list progress",
                syncSavedToAniList,
                onSavedSyncChange,
            )
            SettingSwitch(
                "New episode alerts",
                "Notify when an episode airs for saved or favourite anime",
                releaseNotifications,
                onReleaseNotificationsChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = LibraryStore::clearHistory, modifier = Modifier.padding(horizontal = 8.dp).focusHighlight(RoundedCornerShape(8.dp))) {
                Text("Clear viewing history")
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(9.dp)).clickable { onCheckedChange(!checked) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, Modifier.focusProperties { canFocus = false })
    }
}

@Composable
private fun EmptyPanel(text: String) {
    val device = LocalAppDeviceProfile.current
    Panel(Modifier.padding(horizontal = device.pagePadding)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

private fun joinedLabel(createdAt: Long?): String? {
    if (createdAt == null || createdAt <= 0) return null
    return runCatching {
        "Joined " + Instant.ofEpochSecond(createdAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM yyyy"))
    }.getOrNull()
}

private fun buildCombinedWatchlist(
    profile: AniListProfile?,
    local: List<WatchlistEntry>,
    history: List<HistoryEntry>,
): List<SavedAnimeCardData> {
    val aniListPlanning = profile?.planning.orEmpty().distinctBy { it.media?.id }
    val byMediaId = aniListPlanning.mapNotNull { entry -> entry.media?.id?.let { it to entry } }.toMap()
    val historyById = history.associateBy { it.anilistId }

    return buildList {
        local.forEach { saved ->
            val aniListEntry = byMediaId[saved.anilistId]
            val media = aniListEntry?.media
            add(
                SavedAnimeCardData(
                    id = saved.anilistId,
                    title = media?.title?.preferred ?: saved.title,
                    cover = media?.coverImage?.best ?: saved.cover,
                    format = media?.format ?: saved.format,
                    airingStatus = media?.status,
                    status = aniListEntry?.status,
                    userScore = aniListEntry?.score?.takeIf { it > 0 },
                    averageScore = media?.averageScore ?: saved.averageScore,
                    progress = aniListEntry?.progress ?: historyById[saved.anilistId]?.episodeNumber?.toInt(),
                    totalEpisodes = media?.episodes,
                ),
            )
        }
        aniListPlanning.forEach { aniListEntry ->
            val media = aniListEntry.media ?: return@forEach
            if (local.any { it.anilistId == media.id }) return@forEach
            add(
                SavedAnimeCardData(
                    id = media.id,
                    title = media.title.preferred,
                    cover = media.coverImage.best,
                    format = media.format,
                    airingStatus = media.status,
                    status = aniListEntry.status,
                    userScore = aniListEntry.score.takeIf { it > 0 },
                    averageScore = media.averageScore,
                    progress = aniListEntry.progress,
                    totalEpisodes = media.episodes,
                ),
            )
        }
    }
}

private fun aniListCards(entries: List<MediaListEntry>): List<SavedAnimeCardData> =
    entries.mapNotNull { entry ->
        val media = entry.media ?: return@mapNotNull null
        SavedAnimeCardData(
            id = media.id,
            title = media.title.preferred,
            cover = media.coverImage.best,
            format = media.format,
            airingStatus = media.status,
            status = entry.status,
            userScore = entry.score.takeIf { it > 0 },
            averageScore = media.averageScore,
            progress = entry.progress,
            totalEpisodes = media.episodes,
        )
    }.distinctBy { it.id }

private fun String.toDisplayLabel(): String = when (this) {
    "CURRENT" -> "Watching"
    "REPEATING" -> "Re-watching"
    "PLANNING" -> "Planning"
    "PAUSED" -> "Paused"
    else -> lowercase().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
