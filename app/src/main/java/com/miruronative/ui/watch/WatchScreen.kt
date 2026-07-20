package com.miruronative.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.library.WatchlistEntry
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.StreamItem
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.playback.PlaybackService
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.components.episodeWatchFraction
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

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
    val watchlist by LibraryStore.watchlist.collectAsState()
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    var webFallback by remember { mutableStateOf(false) }
    // TV plays fullscreen from the start; Back drops to the episode/source screen.
    var fullscreen by remember(animeId, showEpisodeListInitially, device.isTv) {
        mutableStateOf(device.isTv && !showEpisodeListInitially)
    }
    val activity = remember(context) { context.findActivity() }
    val currentOnBack by rememberUpdatedState(onBack)
    var embeddedPlaybackStopper by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentEmbeddedPlaybackStopper by rememberUpdatedState(embeddedPlaybackStopper)
    // YouTube-style leave: native playback PAUSES (media stays loaded, so the notification can
    // resume it and re-entering the episode continues in place); the position is committed past
    // the periodic-save throttle so "continue watching" lands exactly where the user left off.
    // Embeds still stop — their WebView dies with this screen, so there is nothing to resume.
    val pauseAndBack = remember {
        {
            currentEmbeddedPlaybackStopper?.invoke()
            vm.commitPlaybackPosition()
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
    // Pause rather than stop: the loaded session powers the notification's resume button,
    // and background media-button events are suppressed so it can't restart by itself.
    DisposableEffect(Unit) {
        onDispose {
            currentEmbeddedPlaybackStopper?.invoke()
            vm.commitPlaybackPosition()
            PlaybackService.pauseActivePlayback()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (webFallback) {
            EmbedWebView(
                url = "https://www.miruro.to/info/$animeId",
                referer = "https://www.miruro.to/",
                modifier = Modifier.fillMaxSize(),
                onFullscreenChanged = { fullscreen = it },
                onProgress = vm::onProgress,
                onPlaybackStopperChanged = { embeddedPlaybackStopper = it },
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
            is UiState.Success -> {
                val saved = watchlist.any { it.anilistId == s.data.anilistId }
                WatchContent(
                    data = s.data,
                    fullscreen = fullscreen || inPictureInPicture,
                    saved = saved,
                    onToggleSaved = {
                        LibraryStore.toggleWatchlist(
                            WatchlistEntry(
                                anilistId = s.data.anilistId,
                                title = s.data.seriesTitle,
                                cover = s.data.artworkUrl,
                                format = s.data.seriesFormat,
                                averageScore = s.data.averageScore,
                            ),
                        )
                    },
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
                    onPlaybackStopperChanged = { embeddedPlaybackStopper = it },
                    onPlayerClosed = vm::commitPlaybackPosition,
                )
            }
        }
    }
}

@Composable
private fun WatchContent(
    data: WatchData,
    fullscreen: Boolean,
    saved: Boolean,
    onToggleSaved: () -> Unit,
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
    onPlaybackStopperChanged: (((() -> Unit)?) -> Unit)? = null,
    onPlayerClosed: () -> Unit = {},
) {
    val device = LocalAppDeviceProfile.current
    val summaryFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    val tvEpisodeListState = rememberLazyListState()
    var hasShownFullscreen by remember { mutableStateOf(fullscreen) }

    // TV: leaving fullscreen must hand focus to the episode/source area — otherwise it can
    // stay inside the player (or an embed WebView) and the D-pad never reaches the list.
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            hasShownFullscreen = true
        } else if (device.isTv) {
            // Start the remote in the episode summary, and return it there after fullscreen.
            // This avoids Android player views becoming a second, invisible D-pad focus owner.
            delay(if (hasShownFullscreen) 180 else 950)
            runCatching { summaryFocus.requestFocus() }
                .onSuccess { DiagnosticsLog.event("Watch TV selector focus requested") }
                .onFailure { DiagnosticsLog.throwable("Watch TV selector focus failed", it) }
        }
    }

    LaunchedEffect(data.currentIndex, fullscreen, device.isTv) {
        if (device.isTv && !fullscreen) {
            // Native player focus can briefly bring a lower control into view during load.
            // Restore the summary as the visual anchor once that handoff settles.
            val returningFromFullscreen = hasShownFullscreen
            delay(if (returningFromFullscreen) 260 else 850)
            tvEpisodeListState.scrollToItem(0)
            if (!returningFromFullscreen) {
                // The initial summary focus request lands just after the first reset; undo the
                // small bring-into-view offset it can apply to the title line.
                delay(300)
                tvEpisodeListState.scrollToItem(0)
            }
        }
    }

    // Fullscreen draws edge-to-edge; otherwise the player must stay clear of the status bar so
    // the clock/battery icons never overlap the video, and its size must come from the actual
    // constraints this screen was given (not LocalConfiguration, which ignores multi-window and
    // navigation rails) so the video always fits the space it really has.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (fullscreen) Modifier else Modifier.statusBarsPadding()),
    ) {
        val availableWidthDp = maxWidth
        val availableHeightDp = maxHeight
        Column(Modifier.fillMaxSize()) {
        val playerModifier = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            val naturalHeight = availableWidthDp * 9f / 16f
            val landscape = availableWidthDp > availableHeightDp
            val maxHeightFraction = when {
                device.isTv -> 0.56f
                landscape -> 0.66f
                else -> 0.62f
            }
            val playerHeight = minOf(naturalHeight, availableHeightDp * maxHeightFraction)
            Modifier.fillMaxWidth().height(playerHeight)
        }
        // In the TV grid the Android player/WebView must not join the Compose focus graph: some
        // embeds retain native focus even after the ring moves, producing two remote owners.
        // Fullscreen remains available as an explicit selector below the summary.
        val playerFocusModifier = if (device.isTv && !fullscreen) {
            Modifier
                .semantics { contentDescription = "Video player" }
        } else {
            Modifier
        }
        Box(playerModifier.then(playerFocusModifier).background(Color.Black)) {
            val stream = data.chosenStream
            // Key on the KIND of player only, never per-episode/per-url: recreating PlayerSurface
            // for every Next tore down and rebuilt the PlayerView + MediaController each episode
            // (the new view was even created before the old one released). On weak TV hardware
            // (Fire TV's OMX.MS.AVC decoder, 192MB heap) that churn froze the UI for seconds and
            // could wedge the video decoder while audio played on. Both PlayerSurface and
            // EmbedWebView already handle stream-url changes internally, reusing the surface,
            // codec chain, and controller connection.
            val playerKind = when {
                device.isTv && !fullscreen -> "tv-idle"
                stream == null -> "none"
                stream.isEmbed || ProviderCatalog.isEmbed(data.provider) -> "embed"
                else -> "native"
            }
            key(playerKind) {
                when {
                    device.isTv && !fullscreen -> {
                        // Keep native PlayerView/WebView focus out of the TV episode grid: the
                        // player only exists in fullscreen. Leaving fullscreen stops playback, so
                        // first bank the position it reached — every resume path reads it.
                        LaunchedEffect(stream?.url) {
                            PlaybackService.stopActivePlayback()
                            onPlayerClosed()
                        }
                        // A real focusable play button (not a decoration): Fire TV users expect
                        // to click play here to continue watching, which re-enters fullscreen.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Play"
                                        onClick(label = "Play") {
                                            onToggleFullscreen()
                                            true
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        val keyCode = event.nativeKeyEvent.keyCode
                                        val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                        if (!activate) {
                                            false
                                        } else {
                                            if (event.type == KeyEventType.KeyUp) onToggleFullscreen()
                                            true
                                        }
                                    }
                                    .focusHighlight(CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .clickable(onClick = onToggleFullscreen)
                                    .focusable()
                                    .size(72.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(46.dp),
                                )
                            }
                        }
                    }
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
                                isFullscreen = fullscreen,
                                onToggleFullscreen = onToggleFullscreen,
                                onFullscreenChanged = onFullscreenChanged,
                                onProgress = onProgress,
                                onPlaybackError = onPlaybackError.takeIf { data.provider == "allanime" },
                                onPlaybackStopperChanged = onPlaybackStopperChanged,
                            )
                            // Embed players often use CSS "web fullscreen" that never reaches the
                            // WebView fullscreen callback, so the app provides its own toggle. On
                            // touch it lives in the player's own control bar; a remote has no
                            // pointer to summon that bar with while the video owns focus, so TV
                            // keeps a corner button of its own.
                            if (device.isTv) IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .focusProperties { canFocus = fullscreen }
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
                    com.miruronative.ui.components.NoFaceLoadingIndicator(size = 72.dp)
                }
            }
            if (!fullscreen) BackButton(onBack, Modifier.align(Alignment.TopStart))
        }

        if (fullscreen) return@Column

        if (!device.isTv) {
            MobileWatchDetails(
                data = data,
                saved = saved,
                onToggleSaved = onToggleSaved,
                focusRequester = sourceFocus,
                onChangeSource = onChangeSource,
                onChangeCategory = onChangeCategory,
                onSelectEpisode = onSelectEpisode,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            return@Column
        }

        val episodeRows = remember(data.episodes, device.episodeColumns) {
            data.episodes.withIndex().chunked(device.episodeColumns)
        }
        val tvHistory by LibraryStore.history.collectAsState()
        val tvResume = tvHistory.firstOrNull { it.anilistId == data.anilistId }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = tvEpisodeListState,
        ) {
            item {
                WatchEpisodeSummary(
                    data = data,
                    saved = saved,
                    onToggleSaved = onToggleSaved,
                    focusRequester = summaryFocus,
                    nextFocusRequester = sourceFocus,
                    modifier = Modifier.padding(
                        start = device.pagePadding,
                        end = device.pagePadding,
                        top = 12.dp,
                        bottom = 6.dp,
                    ),
                )
            }
            item {
                SourceSelectors(
                    data = data,
                    onChangeSource = onChangeSource,
                    onChangeCategory = onChangeCategory,
                    focusRequester = sourceFocus,
                    onToggleFullscreen = onToggleFullscreen,
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
                            watchedFraction = episodeWatchFraction(tvResume, episode.number),
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
}

@Composable
private fun WatchEpisodeSummary(
    data: WatchData,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    focusRequester: FocusRequester? = null,
    nextFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val description = remember(data.anilistId, data.description) {
        data.description?.cleanAniListDescription().orEmpty()
    }
    var descriptionExpanded by remember(data.anilistId, data.current.number) { mutableStateOf(false) }
    val canExpand = description.length > 180
    val localMoreFocus = remember { FocusRequester() }
    val localHeartFocus = remember { FocusRequester() }
    val moreFocus = if (canExpand) focusRequester ?: localMoreFocus else localMoreFocus
    val heartFocus = if (!canExpand) focusRequester ?: localHeartFocus else localHeartFocus
    val focusScope = rememberCoroutineScope()
    var restoreHeartFocus by remember { mutableStateOf(false) }
    val toggleSaved = {
        restoreHeartFocus = nextFocusRequester != null
        onToggleSaved()
    }

    LaunchedEffect(saved) {
        if (restoreHeartFocus) {
            // LibraryStore publishes a new list after the toggle. Restore the TV target after
            // that recomposition instead of allowing focus to fall back to the player/back key.
            delay(60)
            runCatching { heartFocus.requestFocus() }
            restoreHeartFocus = false
        }
    }

    Column(modifier = modifier.fillMaxWidth().focusGroup()) {
        Text(
            text = data.current.title?.takeIf { it.isNotBlank() }
                ?: "Episode ${data.current.displayNumber}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildList {
                data.averageScore?.let { add("$it% score") }
                add("Episode ${data.current.displayNumber}")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )

        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp),
            )
            if (canExpand) {
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .focusRequester(moreFocus)
                        .focusProperties { down = heartFocus }
                        .onPreviewKeyEvent { event ->
                            val keyCode = event.nativeKeyEvent.keyCode
                            if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                            ) {
                                if (event.type == KeyEventType.KeyUp) {
                                    descriptionExpanded = !descriptionExpanded
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                                if (event.type == KeyEventType.KeyUp) {
                                    focusScope.launch {
                                        delay(32)
                                        heartFocus.requestFocus()
                                    }
                                }
                                return@onPreviewKeyEvent true
                            }
                            false
                        }
                        .focusHighlight(RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .semantics {
                            onClick(label = if (descriptionExpanded) "Show less" else "Show more") {
                                descriptionExpanded = !descriptionExpanded
                                true
                            }
                        }
                        .pointerInput(descriptionExpanded) {
                            detectTapGestures { descriptionExpanded = !descriptionExpanded }
                        }
                        .focusable()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (descriptionExpanded) "Less" else "More",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = if (description.isBlank()) 10.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = data.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = data.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                data.popularity?.let { popularity ->
                    Text(
                        text = "${compactPopularity(popularity)} popularity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (saved) "Remove from list" else "Add to list"
                        onClick(label = if (saved) "Remove from list" else "Add to list") {
                            toggleSaved()
                            true
                        }
                    }
                    .focusRequester(heartFocus)
                    .focusProperties {
                        if (canExpand) up = moreFocus
                        nextFocusRequester?.let { down = it }
                    }
                    .onPreviewKeyEvent { event ->
                        val keyCode = event.nativeKeyEvent.keyCode
                        if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        ) {
                            if (event.type == KeyEventType.KeyUp) toggleSaved()
                            return@onPreviewKeyEvent true
                        }
                        when {
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && canExpand -> {
                                if (event.type == KeyEventType.KeyUp) {
                                    focusScope.launch {
                                        delay(32)
                                        moreFocus.requestFocus()
                                    }
                                }
                                true
                            }
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && nextFocusRequester != null -> {
                                if (event.type == KeyEventType.KeyUp) {
                                    focusScope.launch {
                                        delay(32)
                                        nextFocusRequester.requestFocus()
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .focusHighlight(CircleShape)
                    .clip(CircleShape)
                    .pointerInput(saved) { detectTapGestures { toggleSaved() } }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (saved) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

}

private fun String.cleanAniListDescription(): String =
    replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun compactPopularity(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000f)
    else -> value.toString()
}.replace(".0K", "K").replace(".0M", "M")

@Composable
private fun MobileWatchDetails(
    data: WatchData,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    focusRequester: FocusRequester,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pad = LocalAppDeviceProfile.current.pagePadding
    val historyEntries by LibraryStore.history.collectAsState()
    val resume = historyEntries.firstOrNull { it.anilistId == data.anilistId }
    LazyColumn(modifier = modifier) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                WatchEpisodeSummary(
                    data = data,
                    saved = saved,
                    onToggleSaved = onToggleSaved,
                    modifier = Modifier.padding(start = pad, end = pad, top = 16.dp, bottom = 4.dp),
                )
                // Previous/Next pills removed: the player transport and the episode list below
                // already cover episode navigation.
            }
            SourceSelectors(
                data = data,
                onChangeSource = onChangeSource,
                onChangeCategory = onChangeCategory,
                focusRequester = focusRequester,
            )
            data.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = pad, vertical = 5.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Episodes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "  ${data.episodes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(data.episodes, key = { _, item -> item.pipeId }) { index, episode ->
            MobileEpisodeRow(
                episode = episode,
                fallbackImage = data.artworkUrl,
                selected = index == data.currentIndex,
                watchedFraction = episodeWatchFraction(resume, episode.number),
                onClick = { onSelectEpisode(index) },
            )
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun MobileEpisodeRow(
    episode: EpisodeItem,
    fallbackImage: String?,
    selected: Boolean,
    onClick: () -> Unit,
    watchedFraction: Float = 0f,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 5.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = episode.image ?: fallbackImage,
                contentDescription = null,
                modifier = Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Text(
                text = "EP ${episode.displayNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(if (watchedFraction > 0.01f) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            com.miruronative.ui.components.WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(
                text = episode.title?.takeIf { it.isNotBlank() } ?: "Episode ${episode.displayNumber}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (selected) "Now playing" else "Episode ${episode.displayNumber}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Compact server and audio selectors. Choosing a server also makes it the global preference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelectors(
    data: WatchData,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    focusRequester: FocusRequester,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    val servers = remember(data.sourceOptions) { data.sourceOptions.map { it.provider }.distinct() }
    val categoriesByServer = remember(data.sourceOptions) {
        data.sourceOptions
            .groupBy { it.provider }
            .mapValues { (_, options) -> options.map { it.category }.distinct() }
    }
    val audioForServer = remember(data.sourceOptions, data.provider) {
        data.sourceOptions.filter { it.provider == data.provider }.map { it.category }.distinct()
    }
    var showServerDialog by remember { mutableStateOf(false) }
    val mobileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tvDialogFocus = remember { FocusRequester() }

    LaunchedEffect(showServerDialog, device.isTv) {
        if (showServerDialog && device.isTv) {
            delay(120)
            runCatching { tvDialogFocus.requestFocus() }
                .onFailure { DiagnosticsLog.throwable("TV server dialog focus failed", it) }
        }
    }

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
        val alternateAudio = audioForServer.firstOrNull { it != data.category }
        CompactClickablePill(
            label = if (data.category == com.miruronative.data.model.Category.DUB) "Dub" else "Sub",
            enabled = alternateAudio != null,
            active = data.category == com.miruronative.data.model.Category.DUB,
            showArrow = false,
            onClick = { alternateAudio?.let { onChangeCategory(it.api) } },
        )
        if (device.isTv && onToggleFullscreen != null) {
            CompactClickablePill(
                label = "Fullscreen",
                enabled = true,
                showArrow = false,
                onClick = onToggleFullscreen,
            )
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

    if (showServerDialog && !device.isTv) {
        ModalBottomSheet(
            onDismissRequest = { showServerDialog = false },
            sheetState = mobileSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            MobileServerPickerContent(
                data = data,
                servers = servers,
                onSelect = { server ->
                    showServerDialog = false
                    if (server != data.provider || server != data.preferredProvider) {
                        val options = data.sourceOptions.filter { it.provider == server }
                        val nextCategory = options.firstOrNull { it.category == data.category }?.category
                            ?: options.first().category
                        onChangeSource(server, nextCategory.api)
                    }
                },
                onClose = { showServerDialog = false },
            )
        }
    }

    if (showServerDialog && device.isTv) {
        Dialog(
            onDismissRequest = {
                showServerDialog = false
                if (device.isTv) runCatching { focusRequester.requestFocus() }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = device.isTv,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (device.isTv) 0.dp else 84.dp),
                contentAlignment = if (device.isTv) Alignment.Center else Alignment.BottomCenter,
            ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(
                        if (device.isTv) RoundedCornerShape(16.dp)
                        else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        if (device.isTv) RoundedCornerShape(16.dp)
                        else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
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
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val columns = if (device.isTv) 4 else 3
                        val rows = servers.chunked(columns)
                        rows.forEachIndexed { rowIndex, rowCells ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCells.forEachIndexed { columnIndex, server ->
                                    val selected = server == data.provider
                                    val preferred = server == data.preferredProvider
                                    val bg = when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    val selectServer = {
                                        showServerDialog = false
                                        if (device.isTv) runCatching { focusRequester.requestFocus() }
                                        if (!selected || !preferred) {
                                            val options = data.sourceOptions.filter { it.provider == server }
                                            val category = options.firstOrNull { it.category == data.category }?.category
                                                ?: options.first().category
                                            onChangeSource(server, category.api)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .then(
                                                if (rowIndex == 0 && columnIndex == 0) {
                                                    Modifier.focusRequester(tvDialogFocus)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .height(58.dp)
                                            .onPreviewKeyEvent { event ->
                                                val keyCode = event.nativeKeyEvent.keyCode
                                                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                                if (!activate) {
                                                    false
                                                } else {
                                                    if (event.type == KeyEventType.KeyUp) selectServer()
                                                    true
                                                }
                                            }
                                            .focusHighlight(RoundedCornerShape(8.dp))
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bg)
                                            .border(
                                                1.dp,
                                                if (selected || preferred) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable(onClick = selectServer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                categoriesByServer[server].orEmpty().forEach { category ->
                                                    SourceCategoryBadge(category = category, selected = selected)
                                                }
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
}

@Composable
private fun MobileServerPickerContent(
    data: WatchData,
    servers: List<String>,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val categoriesByServer = remember(data.sourceOptions) {
        data.sourceOptions
            .groupBy { it.provider }
            .mapValues { (_, options) -> options.map { it.category }.distinct() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Select preferred server",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (data.preferredProvider == "auto") {
                    "Choose once; it will be tried first for every anime."
                } else {
                    "Preferred: ${ProviderCatalog.label(data.preferredProvider)} · ${servers.size} available"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = FastServerColor,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Fast servers usually start quickest",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val columns = 2
            servers.chunked(columns).forEach { rowServers ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowServers.forEach { server ->
                        val selected = server == data.provider
                        val preferred = server == data.preferredProvider
                        val textColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected || preferred) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(11.dp),
                                )
                                .clickable { onSelect(server) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (ProviderCatalog.isFast(server)) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = "Fast server",
                                        tint = if (selected) textColor else FastServerColor,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = ProviderCatalog.label(server) + if (preferred) " ★" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selected || preferred) FontWeight.Bold else FontWeight.Normal,
                                    color = textColor,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                categoriesByServer[server].orEmpty().forEach { category ->
                                    SourceCategoryBadge(category = category, selected = selected)
                                }
                            }
                        }
                    }
                    repeat(columns - rowServers.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
            Text("Close")
        }
    }
}

@Composable
private fun SourceCategoryBadge(category: Category, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

/** Compact bordered pill that triggers an onClick callback. */
@Composable
private fun CompactClickablePill(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    active: Boolean = false,
    showArrow: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!enabled || !activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusHighlight(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
        if (showArrow) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            com.miruronative.ui.components.WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
    val tvFocusPolicy = if (LocalAppDeviceProfile.current.isTv) {
        // A TV remote already has a dedicated Back key. Keeping this overlay out of the D-pad
        // graph prevents Up/Center from unexpectedly abandoning the episode controls.
        Modifier.focusProperties { canFocus = false }
    } else {
        Modifier
    }
    IconButton(
        onClick = onBack,
        // The app draws edge-to-edge, so keep the button below the clock/battery area whenever
        // the status bar is visible (the inset is zero in fullscreen, where the bars are hidden).
        modifier = modifier
            .then(tvFocusPolicy)
            .statusBarsPadding()
            .padding(4.dp)
            .focusHighlight(RoundedCornerShape(24.dp)),
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
