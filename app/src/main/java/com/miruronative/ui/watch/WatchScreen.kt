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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.model.EpisodeItem
import com.miruronative.playback.PlaybackService
import com.miruronative.ui.UiState
import com.miruronative.ui.components.ErrorBox
import com.miruronative.ui.components.LoadingBox
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight

@Composable
fun WatchScreen(
    animeId: Int,
    provider: String,
    category: String,
    episode: String,
    inPictureInPicture: Boolean = false,
    onBack: () -> Unit,
    onAnimeDetails: () -> Unit,
    vm: WatchViewModel = viewModel(),
) {
    LaunchedEffect(animeId, provider, category, episode) {
        vm.start(animeId, provider, category, episode)
    }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    var webFallback by remember { mutableStateOf(false) }
    // TV plays fullscreen from the start; Back drops to the episode/source screen.
    var fullscreen by remember { mutableStateOf(device.isTv) }
    val activity = remember(context) { context.findActivity() }
    val currentOnBack by rememberUpdatedState(onBack)
    val pauseAndBack = remember {
        {
            PlaybackService.pauseActivePlayback()
            currentOnBack()
        }
    }

    LaunchedEffect(webFallback) {
        if (webFallback) PlaybackService.stopActivePlayback()
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
                LoadingBox()
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
                onAnimeDetails = onAnimeDetails,
                onPrev = vm::prev,
                onNext = vm::next,
                onChangeSource = vm::changeSource,
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
    onAnimeDetails: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangeSource: (String, String) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onWebFallback: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onPlaybackError: (String) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val configuration = LocalConfiguration.current
    var sourceMenuExpanded by remember { mutableStateOf(false) }

    // A dialog rather than a DropdownMenu: dialogs get reliable D-pad focus on TV, and the
    // list is easier to hit on phones too.
    if (sourceMenuExpanded) {
        AlertDialog(
            onDismissRequest = { sourceMenuExpanded = false },
            title = { Text("Streaming source") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    data.sourceOptions.forEach { option ->
                        val selected = option.provider == data.provider && option.category == data.category
                        TextButton(
                            onClick = {
                                sourceMenuExpanded = false
                                if (!selected) onChangeSource(option.provider, option.category.api)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusHighlight(RoundedCornerShape(8.dp)),
                        ) {
                            Text(
                                buildString {
                                    append(ProviderCatalog.label(option.provider))
                                    append(" ")
                                    append(option.category.api.uppercase())
                                    append(" • ${option.episodeCount} ep")
                                    if (!option.hasCurrentEpisode) append(" • first available")
                                    if (selected) append("  ✓")
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sourceMenuExpanded = false }) { Text("Close") }
            },
        )
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
        Box(playerModifier.background(Color.Black)) {
            val stream = data.chosenStream
            when {
                stream == null -> NoSource(onWebFallback)
                stream.isEmbed || ProviderCatalog.isEmbed(data.provider) ->
                    Box(Modifier.fillMaxSize()) {
                        LaunchedEffect(stream.url) { PlaybackService.stopActivePlayback() }
                        EmbedWebView(
                            url = stream.url,
                            referer = stream.referer,
                            modifier = Modifier.fillMaxSize(),
                            skip = data.sources.skip,
                            onNextEpisode = onNext,
                            onFullscreenChanged = onFullscreenChanged,
                            onProgress = onProgress,
                        )
                        // Embed players often use CSS "web fullscreen" that never reaches the
                        // WebView fullscreen callback, so the app provides its own toggle.
                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
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
                )
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
                                (data.current.title?.let { ": $it" } ?: "") +
                                " • ${ProviderCatalog.label(data.provider)} ${data.category.api.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onPrev,
                        enabled = data.hasPrev,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous episode")
                    }
                    IconButton(
                        onClick = onNext,
                        enabled = data.hasNext,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next episode")
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = device.pagePadding, vertical = 0.dp)
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onAnimeDetails,
                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                    ) {
                        Text("Anime page")
                    }
                    TextButton(
                        onClick = { sourceMenuExpanded = true },
                        enabled = data.sourceOptions.isNotEmpty(),
                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                    ) {
                        Text("Source: ${ProviderCatalog.label(data.provider)} ${data.category.api.uppercase()}")
                    }
                }
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
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onBack,
        modifier = modifier.padding(4.dp).focusHighlight(RoundedCornerShape(24.dp)),
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
