package com.miruronative.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miruronative.data.ProviderCatalog
import com.miruronative.data.model.EpisodeItem
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
    vm: WatchViewModel = viewModel(),
) {
    LaunchedEffect(animeId, provider, category, episode) {
        vm.start(animeId, provider, category, episode)
    }
    val state by vm.state.collectAsState()
    var webFallback by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val activity = remember(context) { context.findActivity() }

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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (webFallback) {
            EmbedWebView(
                url = "https://www.miruro.to/info/$animeId",
                referer = "https://www.miruro.to/",
                modifier = Modifier.fillMaxSize(),
                onFullscreenChanged = { fullscreen = it },
            )
            BackButton(onBack, Modifier.align(Alignment.TopStart))
            return@Box
        }

        when (val s = state) {
            is UiState.Loading -> {
                LoadingBox()
                BackButton(onBack, Modifier.align(Alignment.TopStart))
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
                BackButton(onBack, Modifier.align(Alignment.Start))
            }
            is UiState.Success -> WatchContent(
                data = s.data,
                fullscreen = fullscreen || inPictureInPicture,
                onBack = onBack,
                onPrev = vm::prev,
                onNext = vm::next,
                onSelectEpisode = vm::playIndex,
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
    onSelectEpisode: (Int) -> Unit,
    onWebFallback: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onProgress: (Long, Long) -> Unit,
    onPlaybackError: (String) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val configuration = LocalConfiguration.current
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
                    EmbedWebView(
                        url = stream.url,
                        referer = stream.referer,
                        modifier = Modifier.fillMaxSize(),
                        onFullscreenChanged = onFullscreenChanged,
                    )
                else -> PlayerSurface(
                    stream = stream,
                    subtitles = data.sources.subtitles,
                    skip = data.sources.skip,
                    seriesTitle = data.seriesTitle,
                    episodeTitle = "Episode ${data.current.displayNumber}" +
                        (data.current.title?.let { ": $it" } ?: ""),
                    artworkUrl = data.artworkUrl,
                    onEnded = { if (com.miruronative.data.settings.SettingsStore.autoplay.value) onNext() },
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
                Column(Modifier.padding(device.pagePadding)) {
                    Text(
                        text = "Episode ${data.current.displayNumber}" +
                            (data.current.title?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${ProviderCatalog.label(data.provider)} • ${data.category.api.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    data.notice?.let { notice ->
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onPrev,
                            enabled = data.hasPrev,
                            modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = null)
                            Text("Prev", Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(
                            onClick = onNext,
                            enabled = data.hasNext,
                            modifier = Modifier.focusHighlight(RoundedCornerShape(24.dp)),
                        ) {
                            Text("Next", Modifier.padding(end = 4.dp))
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                        }
                    }
                }
            }
            item {
                Text(
                    "Episodes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = device.pagePadding, bottom = 4.dp),
                )
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            episode.displayNumber,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        )
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
