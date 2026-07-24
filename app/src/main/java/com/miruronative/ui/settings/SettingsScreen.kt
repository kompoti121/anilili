package com.miruronative.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.miruronative.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.auth.MalAuthManager
import com.miruronative.data.cache.CacheManager
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.MalExportFile
import com.miruronative.data.reminder.AutomaticReleaseManager
import com.miruronative.data.reminder.ReleaseSyncScheduler
import com.miruronative.data.settings.DefaultQuality
import com.miruronative.data.settings.DownloadDestination
import com.miruronative.data.settings.DownloadQuality
import com.miruronative.data.settings.SettingsStore
import com.miruronative.data.settings.MenuLanguage
import com.miruronative.data.update.UpdateManager
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.UiState
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.adaptive.rememberScreenReaderActive
import com.miruronative.ui.components.CaptionAppearanceDialog
import com.miruronative.ui.components.LocalAppChromeBottomInset
import com.miruronative.ui.components.ScrollAwareTopBar
import com.miruronative.ui.profile.AniListProfile
import com.miruronative.ui.profile.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val screenReaderActive = rememberScreenReaderActive()
    val token by AuthManager.token.collectAsState()
    val malLoggedIn by MalAuthManager.loggedIn.collectAsState()
    val profileState by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val autoSkip by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoSync by SettingsStore.autoSyncAniList.collectAsState()
    val preferDub by SettingsStore.preferDub.collectAsState()
    val defaultQuality by SettingsStore.defaultQuality.collectAsState()
    val downloadQuality by SettingsStore.downloadQuality.collectAsState()
    val downloadDestination by SettingsStore.downloadDestination.collectAsState()
    val releaseNotifications by SettingsStore.releaseNotifications.collectAsState()
    val hideAdultContent by SettingsStore.hideAdultContent.collectAsState()
    val subtitlesWithDub by SettingsStore.subtitlesWithDub.collectAsState()
    val updateCheckOnLaunch by SettingsStore.updateCheckOnLaunch.collectAsState()
    val syncSavedToAniList by SettingsStore.syncSavedToAniList.collectAsState()
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    val updateState by UpdateManager.state.collectAsState()
    val profile = (profileState as? UiState.Success<AniListProfile>)?.data
    val scope = rememberCoroutineScope()
    var pendingMalExport by remember { mutableStateOf<MalExportFile?>(null) }
    var malExportBusy by remember { mutableStateOf(false) }
    var malExportMessage by remember { mutableStateOf<String?>(null) }
    var malImportBusy by remember { mutableStateOf(false) }
    var malImportMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }
    var tvDiagnosticsVisible by remember { mutableStateOf(false) }
    var captionAppearanceVisible by remember { mutableStateOf(false) }
    var cacheUsage by remember { mutableStateOf<Long?>(null) }
    var cacheClearing by remember { mutableStateOf(false) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(token, malLoggedIn) { vm.loadIfLoggedIn() }
    LaunchedEffect(Unit) {
        cacheUsage = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
    }

    if (tvDiagnosticsVisible) {
        TvDiagnosticsShareDialog(onDismiss = { tvDiagnosticsVisible = false })
    }

    if (captionAppearanceVisible) {
        CaptionAppearanceDialog(
            onDismiss = { captionAppearanceVisible = false },
            footnote = "Applies to the built-in player. Servers that play in a web view render " +
                "their own subtitles and may ignore some of these.",
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        if (granted) ReleaseSyncScheduler.runNow(context)
    }
    val malExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri ->
        val file = pendingMalExport
        pendingMalExport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(file.xml.toByteArray(Charsets.UTF_8))
            } ?: error("Couldn't open export file")
        }.onSuccess {
            malExportMessage = buildString {
                append("Exported ${file.exportedCount} anime")
                if (file.skippedCount > 0) append("; skipped ${file.skippedCount} without MAL IDs")
            }
        }.onFailure { error ->
            malExportMessage = error.message ?: "MAL export failed"
        }
    }
    // MAL serves exports as .xml.gz, which file pickers report under assorted mime types, so the
    // filter stays wide open and the parser decides.
    val malImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            malImportBusy = true
            malImportMessage = null
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Couldn't open that file")
                }
                vm.importMalXml(bytes)
            }.onSuccess { summary ->
                malImportMessage = buildString {
                    append("Imported ${summary.added} new anime")
                    if (summary.alreadySaved > 0) append("; ${summary.alreadySaved} already saved")
                    if (summary.unmatched > 0) append("; ${summary.unmatched} not found on AniList")
                }
            }.onFailure { error ->
                malImportMessage = error.message ?: "MAL import failed"
            }
            malImportBusy = false
        }
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

    fun setWatchlistSync(enabled: Boolean) {
        SettingsStore.setSyncSavedToAniList(enabled)
        if (enabled) {
            LibraryStore.syncSavedToRemote()
            vm.loadIfLoggedIn(refresh = true)
        }
    }

    fun exportMal() {
        if (malExportBusy) return
        scope.launch {
            malExportBusy = true
            malExportMessage = null
            runCatching { vm.buildMalExport(profile, watchlist, history) }
                .onSuccess { file ->
                    if (file.exportedCount == 0) {
                        malExportMessage = "No MAL-mapped anime to export"
                    } else {
                        pendingMalExport = file
                        malExportLauncher.launch(file.fileName)
                    }
                }
                .onFailure { error -> malExportMessage = error.message ?: "MAL export failed" }
            malExportBusy = false
        }
    }

    fun clearCache() {
        if (cacheClearing) return
        scope.launch {
            cacheClearing = true
            cacheMessage = null
            val before = cacheUsage ?: withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
            withContext(Dispatchers.IO) { CacheManager.clear(context) }
            val after = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
            cacheUsage = after
            cacheMessage = "Freed ${Formatter.formatShortFileSize(context, (before - after).coerceAtLeast(0))}"
            cacheClearing = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar { TopAppBar(
                title = {
                    Text(if (menuLanguage.usesSpanish()) "Ajustes" else "Settings", fontWeight = FontWeight.Black)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            ) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Scroll padding, not layout padding: rows travel under the bars instead of the list
            // being shunted about by them.
            contentPadding = PaddingValues(
                start = device.pagePadding,
                end = device.pagePadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + LocalAppChromeBottomInset.current + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { SettingsSectionTitle("Playback") }
            item { SettingSwitch("Autoplay next episode", "Continue automatically", autoplay, SettingsStore::setAutoplay) }
            item { SettingSwitch("Auto-skip intro and outro", "Use provider skip times when available", autoSkip, SettingsStore::setAutoSkipIntroOutro) }
            item {
                DefaultQualitySetting(
                    selected = defaultQuality,
                    onSelect = SettingsStore::setDefaultQuality,
                )
            }
            item { SettingSwitch("Prefer dubbed audio", "Use dub first when available", preferDub, SettingsStore::setPreferDub) }
            item {
                SettingSwitch(
                    "Subtitles with dubbed audio",
                    "Show subtitles on dubbed episodes too (applies from the next episode)",
                    subtitlesWithDub,
                    SettingsStore::setSubtitlesWithDub,
                )
            }
            item {
                SettingsAction(
                    title = "Caption appearance",
                    icon = { Icon(Icons.Default.ClosedCaption, contentDescription = null) },
                    enabled = true,
                    onClick = { captionAppearanceVisible = true },
                )
            }
            item { SectionDivider() }

            if (device.isTv) {
                item { SettingsSectionTitle("Accessibility") }
                item {
                    SettingsAction(
                        title = "Spoken feedback: ${if (screenReaderActive) "On" else "Off"}",
                        icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                        enabled = true,
                        onClick = { openAccessibilitySettings(context) },
                    )
                }
                item {
                    Text(
                        "Off by default. Opens Android TV Accessibility settings for viewers who want narration.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                item { SectionDivider() }
            }

            item { SettingsSectionTitle("Downloads") }
            item {
                DownloadQualitySetting(
                    selected = downloadQuality,
                    onSelect = SettingsStore::setDownloadQuality,
                )
            }
            item {
                DownloadDestinationSetting(
                    selected = downloadDestination,
                    deviceDownloadsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onSelect = SettingsStore::setDownloadDestination,
                )
            }
            item { SectionDivider() }

            item { SettingsSectionTitle("Content") }
            item {
                SettingSwitch(
                    "Hide adult content",
                    "Keep hentai out of Home, Search, Browse, and Schedule",
                    hideAdultContent,
                    SettingsStore::setHideAdultContent,
                )
            }
            item { SectionDivider() }

            item { SettingsSectionTitle("List sync (AniList / MyAnimeList)") }
            item { SettingSwitch("Sync episode progress", "Update watched episodes while playing", autoSync, SettingsStore::setAutoSyncAniList) }
            item {
                SettingSwitch(
                    "Sync watchlist with Planning",
                    "Import Planning after login and add new saves without replacing active progress",
                    syncSavedToAniList,
                    ::setWatchlistSync,
                )
            }
            item { SectionDivider() }

            item { SettingsSectionTitle("Notifications") }
            item {
                SettingSwitch(
                    "Notification alerts",
                    "Logged in: AniList notifications; logged out: saved anime releases",
                    releaseNotifications,
                    ::setReleaseNotifications,
                )
            }
            item { SectionDivider() }

            item { SettingsSectionTitle("Data") }
            item {
                SettingsAction(
                    title = if (malExportBusy) "Preparing MyAnimeList export..." else "Export MyAnimeList XML",
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    enabled = !malExportBusy && ((token == null && !malLoggedIn) || profile != null),
                    onClick = ::exportMal,
                )
            }
            malExportMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                SettingsAction(
                    title = if (malImportBusy) "Importing MyAnimeList XML..." else "Import MyAnimeList XML",
                    icon = { Icon(Icons.Default.Upload, contentDescription = null) },
                    enabled = !malImportBusy,
                    onClick = {
                        malImportLauncher.launch(
                            arrayOf("text/xml", "application/xml", "application/gzip", "application/octet-stream", "*/*"),
                        )
                    },
                )
            }
            malImportMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                SettingsAction(
                    title = "Clear viewing history",
                    icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                    enabled = history.isNotEmpty(),
                    onClick = LibraryStore::clearHistory,
                )
            }
            item {
                SettingsAction(
                    title = cacheUsage.let { usage ->
                        when {
                            cacheClearing -> "Clearing cache..."
                            usage != null -> "Clear cache (${Formatter.formatShortFileSize(context, usage)})"
                            else -> "Clear cache"
                        }
                    },
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    enabled = !cacheClearing && (cacheUsage ?: 0L) > 0L,
                    onClick = ::clearCache,
                )
            }
            item {
                Text(
                    cacheMessage
                        ?: "Streamed video and images kept on this device for faster playback. The video cache auto-trims at 512 MB.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            item { SectionDivider() }

            item { SettingsSectionTitle(if (menuLanguage.usesSpanish()) "Aplicación" else "App") }
            item {
                MenuLanguageSetting(
                    selected = menuLanguage,
                    onSelect = SettingsStore::setMenuLanguage,
                )
            }
            item {
                SettingsAction(
                    title = "Share diagnostics",
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    enabled = true,
                    onClick = {
                        diagnosticsMessage = null
                        if (device.isTv) {
                            // TV has no ACTION_SEND targets — share over the LAN + Downloads instead.
                            tvDiagnosticsVisible = true
                        } else {
                            DiagnosticsLog.share(context)
                                .onFailure { diagnosticsMessage = it.message ?: "Couldn't share diagnostics" }
                        }
                    },
                )
            }
            diagnosticsMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                SettingSwitch(
                    "Check for updates on launch",
                    "Prompt when a new version is available each time the app opens",
                    updateCheckOnLaunch,
                    SettingsStore::setUpdateCheckOnLaunch,
                )
            }
            item {
                SettingsAction(
                    title = when (updateState) {
                        is UpdateManager.State.Checking -> "Checking for updates..."
                        is UpdateManager.State.Downloading -> "Downloading update..."
                        else -> "Check for updates"
                    },
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    enabled = updateState !is UpdateManager.State.Checking &&
                        updateState !is UpdateManager.State.Downloading,
                    onClick = { UpdateManager.check(context, manual = true) },
                )
            }
            item {
                Text(
                    when (val state = updateState) {
                        is UpdateManager.State.UpToDate -> {
                            if (state.latestPublishedVersion == UpdateManager.currentVersion) {
                                "You're on the latest published version (v${UpdateManager.currentVersion})"
                            } else {
                                "Installed v${UpdateManager.currentVersion} · published v${state.latestPublishedVersion}"
                            }
                        }
                        else -> "Version ${UpdateManager.currentVersion}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            item { SectionDivider() }
            item {
                SettingsAction(
                    title = "Share Anilili with friends",
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    enabled = true,
                    onClick = { shareAnilili(context, openWebsite = device.isTv) },
                )
            }
            item {
                SettingsAction(
                    title = "Join Telegram Group",
                    icon = { Icon(painterResource(R.drawable.ic_telegram), contentDescription = null) },
                    enabled = true,
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/anililiapk"))
                            context.startActivity(intent)
                        }
                    },
                )
            }
            item {
                SettingsAction(
                    title = "GitHub Repository",
                    icon = { Icon(painterResource(R.drawable.ic_github), contentDescription = null) },
                    enabled = true,
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/kompoti121/anilili"))
                            context.startActivity(intent)
                        }
                    },
                )
            }
        }
    }
}

private const val ANILILI_WEBSITE = "https://kompoti121.github.io/Anilili/"

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.onFailure {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }
}

private fun shareAnilili(context: Context, openWebsite: Boolean) {
    val website = Uri.parse(ANILILI_WEBSITE)
    if (openWebsite) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, website)) }
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Anilili anime app")
        putExtra(
            Intent.EXTRA_TEXT,
            "Try Anilili for anime on Android and Android TV: $ANILILI_WEBSITE",
        )
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share Anilili"))
    }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, website)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultQualitySetting(
    selected: DefaultQuality,
    onSelect: (DefaultQuality) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Default quality",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Picked automatically when an episode starts in the built-in player",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Five chips outgrow a single row on narrow portrait widths, so let them wrap.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            DefaultQuality.entries.forEach { quality ->
                FilterChip(
                    selected = selected == quality,
                    onClick = { onSelect(quality) },
                    label = { Text(quality.label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadQualitySetting(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Default download resolution",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Limits the saved HLS rendition; direct video files keep their source resolution",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            DownloadQuality.entries.forEach { quality ->
                FilterChip(
                    selected = selected == quality,
                    onClick = { onSelect(quality) },
                    label = { Text(quality.label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadDestinationSetting(
    selected: DownloadDestination,
    deviceDownloadsSupported: Boolean,
    onSelect: (DownloadDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Default download destination",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (deviceDownloadsSupported) {
                "Device Downloads and Both are offered for direct MP4 sources. HLS stays in Anilili."
            } else {
                "Public device downloads require Android 10 or newer. HLS stays in Anilili."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            DownloadDestination.entries.forEach { destination ->
                val enabled = deviceDownloadsSupported || destination == DownloadDestination.APP_ONLY
                FilterChip(
                    selected = selected == destination && enabled,
                    onClick = { onSelect(destination) },
                    enabled = enabled,
                    label = { Text(destination.label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@Composable
private fun MenuLanguageSetting(
    selected: MenuLanguage,
    onSelect: (MenuLanguage) -> Unit,
) {
    val spanish = selected.usesSpanish()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            if (spanish) "Idioma del menú" else "Menu language",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (spanish) "Cambia las etiquetas de navegación principales" else "Changes the main navigation labels",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            MenuLanguage.entries.forEach { language ->
                val label = when (language) {
                    MenuLanguage.SYSTEM -> if (spanish) "Sistema" else "System"
                    MenuLanguage.ENGLISH -> if (spanish) "Inglés" else "English"
                    MenuLanguage.SPANISH -> "Español"
                }
                FilterChip(
                    selected = selected == language,
                    onClick = { onSelect(language) },
                    label = { Text(label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
private fun SettingsAction(
    title: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(8.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        icon()
        Text(title, modifier = Modifier.padding(start = 10.dp).weight(1f))
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 10.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = .7f),
    )
}
