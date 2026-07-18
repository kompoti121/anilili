package com.miruronative.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.StreamItem
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.playback.PlaybackService
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.delay

@Composable
fun WatchScreen(
    animeId: Int,
    provider: String,
    category: String,
    episode: String,
    showEpisodeListInitially: Boolean = false,
    inPictureInPicture: Boolean = false,
    onBack: () -> Unit,
    vm: WatchViewModel = viewModel(),
) {
    LaunchedEffect(animeId, provider, category, episode) {
        DiagnosticsLog.event("WatchScreen composed id=$animeId provider=$provider category=$category episode=$episode")
        vm.start(animeId, provider, category, episode)
    }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    var webFallback by remember { mutableStateOf(false) }
    // TV plays fullscreen from the start; Back drops to the episode/source screen.
    var fullscreen by remember(animeId, showEpisodeListInitially, device.isTv) {
        mutableStateOf(device.isTv && !showEpisodeListInitially)
    }
    val activity = remember(context) { context.findActivity() }
    val currentOnBack by rememberUpdatedState(onBack)
    val pauseAndBack = remember {
        {
            PlaybackService.pauseActivePlayback()
            currentOnBack()
        }
    }

    LaunchedEffect(webFallback) {
        DiagnosticsLog.event("WatchScreen webFallback=$webFallback")
        if (webFallback) PlaybackService.stopActivePlayback()
    }

    LaunchedEffect(state, webFallback) {
        when (val s = state) {
            is UiState.Loading -> {
                delay(10_000)
                if (state is UiState.Loading && !webFallback) {
                    DiagnosticsLog.event("WatchScreen still loading after 10000ms id=$animeId provider=$provider")
                }
            }
            is UiState.Error -> DiagnosticsLog.event("WatchScreen error visible message=${s.message.take(160)}")
            is UiState.Success -> {
                val stream = s.data.chosenStream
                DiagnosticsLog.event(
                    "WatchScreen success visible provider=${s.data.provider} episode=${s.data.current.displayNumber} " +
                        "stream=${stream?.let { if (it.isEmbed) "embed" else if (it.isHls) "hls" else "direct" } ?: "none"} " +
                        "resolving=${s.data.isResolving}",
                )
            }
        }
    }

    // Drive orientation + system bars from the fullscreen flag; restore on leave.
    DisposableEffect(fullscreen, device.isTv) {
        val window = activity?.window
        if (activity != null && window != null) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = activity?.window
            if (activity != null && w != null) {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Back exits fullscreen first, then the screen.
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen) { pauseAndBack() }

    // Pause however the screen is left (back, Anime page, notification nav) — not just the
    // Back gesture. PiP keeps this screen composed, so picture-in-picture is unaffected.
    DisposableEffect(Unit) {
        onDispose { PlaybackService.pauseActivePlayback() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (webFallback) {
            EmbedWebView(
                url = "https://www.miruro.to/info/$animeId",
                referer = "https://www.miruro.to/",
                modifier = Modifier.fillMaxSize(),
                onFullscreenChanged = { fullscreen = it },
                onProgress = vm::onProgress,
            )
            BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            return@Box
        }

        when (val s = state) {
            is UiState.Loading -> {
                // Only surface the "this can take a moment" note once loading is genuinely slow,
                // so it doesn't flash by on the common fast (Miruro-first) path.
                var showSlowNote by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(1_500)
                    showSlowNote = true
                }
                val loadingStatus by vm.loadingStatus.collectAsState()
                LoadingBox(
                    message = loadingStatus ?: if (showSlowNote) {
                        "Finding a source for this episode.\n" +
                            "The first time you open a title we check every server, so it can take a few seconds."
                    } else {
                        null
                    },
                )
                BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            }
            is UiState.Error -> Column(Modifier.fillMaxSize()) {
                ErrorBox(s.message, onRetry = vm::retry, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { webFallback = true },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 24.dp)
                        .focusHighlight(RoundedCornerShape(20.dp)),
                ) { Text("Open in web player") }
                BackButton(pauseAndBack, Modifier.align(Alignment.Start))
            }
            is UiState.Success -> WatchContent(
                data = s.data,
                fullscreen = fullscreen || inPictureInPicture,
                onBack = pauseAndBack,
                onPrev = vm::prev,
                onNext = vm::next,
                onChangeSource = vm::changeSource,
                onChangeCategory = vm::changeCategory,
                onSelectEpisode = { index ->
                    if (device.isTv) fullscreen = true
                    vm.playIndex(index)
                },
                onWebFallback = { webFallback = true },
                onToggleFullscreen = { fullscreen = !fullscreen },
                onFullscreenChanged = { fullscreen = it },
                onProgress = vm::onProgress,
                onPlaybackError = vm::onPlaybackError,
            )
        }
    }
}

@Composable
private fun WatchContent(
    data: WatchData,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onWebFallback: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onPlaybackError: (String, String, Long) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val configuration = LocalConfiguration.current
    val listFocus = remember { FocusRequester() }

    // TV: leaving fullscreen must hand focus to the episode/source area — otherwise it can
    // stay inside the player (or an embed WebView) and the D-pad never reaches the list.
    LaunchedEffect(fullscreen) {
        if (device.isTv && !fullscreen) {
            // Wait until the non-fullscreen controls have been measured before requesting focus.
            delay(120)
            runCatching { listFocus.requestFocus() }
                .onSuccess { DiagnosticsLog.event("Watch TV selector focus requested") }
                .onFailure { DiagnosticsLog.throwable("Watch TV selector focus failed", it) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val playerModifier = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            val naturalHeightDp = configuration.screenWidthDp * 9f / 16f
            val landscape = configuration.screenWidthDp > configuration.screenHeightDp
            val maxHeightFraction = when {
                device.isTv -> 0.62f
                landscape -> 0.66f
                else -> 1f
            }
            val playerHeightDp = minOf(
                naturalHeightDp,
                configuration.screenHeightDp * maxHeightFraction,
            )
            Modifier.fillMaxWidth().height(playerHeightDp.dp)
        }
        // TV, grid view: the player itself is the D-pad target, so Up from the selectors reaches
        // it and Center opens it fullscreen (where the remote controls already live). The focus
        // holder is this Compose box, never the player surface: an embed WebView that can take
        // focus autofocuses on page load and traps the D-pad inside the embed's own HTML.
        val playerFocusModifier = if (device.isTv && !fullscreen) {
            Modifier
                .focusHighlight(RoundedCornerShape(6.dp))
                .onPreviewKeyEvent { event ->
                    val enters = event.key == Key.DirectionCenter || event.key == Key.Enter
                    if (event.type == KeyEventType.KeyDown && enters) {
                        onToggleFullscreen()
                        true
                    } else {
                        false
                    }
                }
                // Screen readers consume the D-pad, so the key handler above never fires under
                // TalkBack; this semantic action is the accessible way into the fullscreen player.
                .semantics {
                    contentDescription = "Video player"
                    onClick(label = "Open fullscreen player") {
                        onToggleFullscreen()
                        true
                    }
                }
                .focusable()
        } else {
            Modifier
        }
        Box(playerModifier.then(playerFocusModifier).background(Color.Black)) {
            val stream = data.chosenStream
            key(data.provider, data.category, data.current.number, stream?.url, data.playbackGeneration) {
                when {
                    stream == null -> NoSource(onWebFallback)
                    stream.isEmbed || ProviderCatalog.isEmbed(data.provider) ->
                        Box(Modifier.fillMaxSize()) {
                            LaunchedEffect(stream.url) { PlaybackService.stopActivePlayback() }
                            EmbedEpisodeNavigationEffect(
                                hasPrevious = data.hasPrev,
                                hasNext = data.hasNext,
                                onPrevious = onPrev,
                                onNext = onNext,
                            )
                            EmbedWebView(
                                url = stream.url,
                                referer = stream.referer,
                                modifier = Modifier.fillMaxSize(),
                                qualityStreams = data.sources.embedStreams,
                                startPositionMs = data.startPositionMs,
                                skip = data.sources.skip,
                                onPreviousEpisode = onPrev,
                                onNextEpisode = onNext,
                                hasPreviousEpisode = data.hasPrev,
                                hasNextEpisode = data.hasNext,
                                focusPlayerOnStart = fullscreen,
                                onFullscreenChanged = onFullscreenChanged,
                                onProgress = onProgress,
                                onPlaybackError = onPlaybackError.takeIf { data.provider == "allanime" },
                            )
                            // Embed players often use CSS "web fullscreen" that never reaches the
                            // WebView fullscreen callback, so the app provides its own toggle.
                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(4.dp)
                                    .focusHighlight(RoundedCornerShape(24.dp)),
                            ) {
                                Icon(
                                    if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                                    tint = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }
                    else -> PlayerSurface(
                        stream = stream,
                        qualityStreams = data.sources.streams.filterNot(StreamItem::isEmbed),
                        subtitles = data.sources.subtitles,
                        skip = data.sources.skip,
                        seriesTitle = data.seriesTitle,
                        episodeTitle = "Episode ${data.current.displayNumber}" +
                            (data.current.title?.let { ": $it" } ?: ""),
                        artworkUrl = data.artworkUrl,
                        animeId = data.anilistId,
                        provider = data.provider,
                        category = data.category.api,
                        episode = data.current.displayNumber,
                        onEnded = { if (com.miruronative.data.settings.SettingsStore.autoplay.value) onNext() },
                        onNextEpisode = onNext,
                        onError = onPlaybackError,
                        modifier = Modifier.fillMaxSize(),
                        onToggleFullscreen = onToggleFullscreen,
                        startPositionMs = data.startPositionMs,
                        onProgress = onProgress,
                        onPreviousEpisode = onPrev,
                        hasNextEpisode = data.hasNext,
                        hasPreviousEpisode = data.hasPrev,
                        focusPlayerOnStart = fullscreen,
                        isFullscreen = fullscreen,
                    )
                }
            }
            if (data.isResolving) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            if (!fullscreen) BackButton(onBack, Modifier.align(Alignment.TopStart))
        }

        if (fullscreen) return

        val episodeRows = remember(data.episodes, device.episodeColumns) {
            data.episodes.withIndex().chunked(device.episodeColumns)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item {
                Row(
                    Modifier
                        .padding(
                            start = device.pagePadding,
                            end = device.pagePadding,
                            top = 14.dp,
                            bottom = 8.dp,
                        )
                        .fillMaxWidth()
                        .focusGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Episodes",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Episode ${data.current.displayNumber}" +
                                (data.current.title?.let { ": $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SourceSelectors(
                    data = data,
                    onChangeSource = onChangeSource,
                    onChangeCategory = onChangeCategory,
                    focusRequester = listFocus,
                )
                data.notice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = device.pagePadding,
                            end = device.pagePadding,
                            bottom = 6.dp,
                        ),
                    )
                }
            }
            items(episodeRows) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = device.pagePadding, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (index, episode) ->
                        EpisodeChip(
                            episode = episode,
                            selected = index == data.currentIndex,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelectEpisode(index) },
                        )
                    }
                    repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Compact server and audio selectors. Choosing a server also makes it the global preference. */
@Composable
private fun SourceSelectors(
    data: WatchData,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    val device = LocalAppDeviceProfile.current
    val servers = remember(data.sourceOptions) { data.sourceOptions.map { it.provider }.distinct() }
    val audioForServer = remember(data.sourceOptions, data.provider) {
        data.sourceOptions.filter { it.provider == data.provider }.map { it.category }.distinct()
    }
    // While the Anivexa catalog is still loading, list the servers we're still checking so their
    // absence reads as "loading", not "unavailable".
    val pendingServers = remember(servers, data.isLoadingMoreSources) {
        if (data.isLoadingMoreSources) ProviderCatalog.anivexaProviders.filterNot { it in servers } else emptyList()
    }
    var showServerDialog by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 4.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Server also carries the TV focus anchor for leaving fullscreen.
        CompactClickablePill(
            label = ProviderCatalog.label(data.provider) +
                if (data.provider == data.preferredProvider) " ★" else "",
            enabled = servers.isNotEmpty(),
            focusRequester = focusRequester,
            onClick = { showServerDialog = true }
        )
        CompactDropdown(
            label = data.category.api.uppercase(),
            enabled = audioForServer.size > 1,
        ) { dismiss ->
            audioForServer.forEach { category ->
                val selected = category == data.category
                DropdownMenuItem(
                    text = {
                        Text(
                            category.api.uppercase(),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        dismiss()
                        if (!selected) onChangeCategory(category.api)
                    },
                )
            }
        }
        if (data.isLoadingMoreSources) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "More servers…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showServerDialog) {
        Dialog(onDismissRequest = {
            showServerDialog = false
            if (device.isTv) runCatching { focusRequester.requestFocus() }
        }) {
            Box(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Select preferred server",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (data.preferredProvider == "auto") {
                                "Choose once; it will be tried first for every anime."
                            } else {
                                "Preferred: ${ProviderCatalog.label(data.preferredProvider)} · ${servers.size} available"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = FastServerColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Fast servers — these usually start quickest",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (pendingServers.isNotEmpty()) {
                            Text(
                                text = "Checking more servers…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val columns = if (device.isTv) 4 else 3
                        // Ready servers first, then the ones we're still checking (spinner cells).
                        val cells = servers.map { it to true } + pendingServers.map { it to false }
                        val rows = cells.chunked(columns)
                        rows.forEach { rowCells ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCells.forEach { (server, ready) ->
                                    val selected = ready && server == data.provider
                                    val preferred = ready && server == data.preferredProvider
                                    val bg = when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        ready -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    }
                                    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .then(if (ready) Modifier.focusHighlight(RoundedCornerShape(8.dp)) else Modifier)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bg)
                                            .border(
                                                1.dp,
                                                if (selected || preferred) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .then(
                                                if (ready) {
                                                    Modifier.clickable {
                                                        showServerDialog = false
                                                        if (device.isTv) runCatching { focusRequester.requestFocus() }
                                                        if (!selected || !preferred) {
                                                            val options = data.sourceOptions.filter { it.provider == server }
                                                            val category = options.firstOrNull { it.category == data.category }?.category
                                                                ?: options.first().category
                                                            onChangeSource(server, category.api)
                                                        }
                                                    }
                                                } else {
                                                    Modifier
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (ready) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                            ) {
                                                if (ProviderCatalog.isFast(server)) {
                                                    Icon(
                                                        Icons.Default.Bolt,
                                                        contentDescription = "Fast server",
                                                        tint = if (selected) textColor else FastServerColor,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                                Text(
                                                    text = ProviderCatalog.label(server) + if (preferred) " ★" else "",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (selected || preferred) FontWeight.Bold else FontWeight.Normal,
                                                    color = textColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    text = ProviderCatalog.label(server),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                                repeat(columns - rowCells.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            showServerDialog = false
                            if (device.isTv) runCatching { focusRequester.requestFocus() }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/** Compact bordered pill that triggers an onClick callback. */
@Composable
private fun CompactClickablePill(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusHighlight(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact bordered pill that opens a dropdown menu. */
@Composable
private fun CompactDropdown(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusHighlight(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun EpisodeChip(
    episode: EpisodeItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        episode.filler -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = modifier
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
    }
}

@Composable
private fun NoSource(onWebFallback: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No playable source on this server.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            TextButton(
                onClick = onWebFallback,
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            ) { Text("Open in web player") }
        }
    }
}

@Composable
private fun EmbedEpisodeNavigationEffect(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val currentHasPrevious by rememberUpdatedState(hasPrevious)
    val currentHasNext by rememberUpdatedState(hasNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)

    DisposableEffect(Unit) {
        val navigator: (Int) -> Unit = { direction ->
            DiagnosticsLog.event("Embed player episode navigator direction=$direction")
            when {
                direction > 0 && currentHasNext -> currentOnNext()
                direction < 0 && currentHasPrevious -> currentOnPrevious()
            }
        }
        PlaybackService.episodeNavigator = navigator
        DiagnosticsLog.event("Embed player episode navigator registered hasPrev=$hasPrevious hasNext=$hasNext")
        onDispose {
            if (PlaybackService.episodeNavigator === navigator) {
                PlaybackService.episodeNavigator = null
            }
            DiagnosticsLog.event("Embed player episode navigator cleared")
        }
    }
}

/** Amber bolt tint for the fast-server badge; readable on both selected and idle cells. */
private val FastServerColor = Color(0xFFFFB300)

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onBack,
        // The app draws edge-to-edge, so keep the button below the clock/battery area whenever
        // the status bar is visible (the inset is zero in fullscreen, where the bars are hidden).
        modifier = modifier.statusBarsPadding().padding(4.dp).focusHighlight(RoundedCornerShape(24.dp)),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
