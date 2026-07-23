package com.miruronative.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miruronative.data.model.StreamItem
import com.miruronative.playback.EpisodeDownloadState
import com.miruronative.playback.EpisodeDownloads
import com.miruronative.playback.PlaybackService
import com.miruronative.ui.adaptive.focusHighlight
import com.miruronative.ui.nav.Routes
import androidx.compose.foundation.shape.CircleShape

/** Full player entry point for an episode already persisted in Media3's download cache. */
@Composable
fun DownloadedEpisodeScreen(
    downloadId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val downloads by EpisodeDownloads.downloads(context).collectAsState()
    val download = downloads.firstOrNull { it.id == downloadId }
    var playbackError by remember(downloadId) { mutableStateOf<String?>(null) }
    val leave = {
        PlaybackService.pauseActivePlayback()
        onBack()
    }

    BackHandler(onBack = leave)
    DisposableEffect(Unit) {
        onDispose { PlaybackService.pauseActivePlayback() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            download == null -> DownloadMessage(
                title = "Download not found",
                message = "It may have been removed from this device.",
            )
            download.state != EpisodeDownloadState.COMPLETED -> DownloadMessage(
                title = "Episode is not ready",
                message = when (download.state) {
                    EpisodeDownloadState.FAILED -> "The download failed. Remove it and try again from the watch page."
                    EpisodeDownloadState.REMOVING -> "This episode is being removed."
                    else -> download.percent?.let { "Downloaded ${it.toInt()}%." }
                        ?: "The download is still in progress."
                },
            )
            playbackError != null -> DownloadMessage(
                title = "Could not play download",
                message = playbackError.orEmpty(),
            )
            else -> {
                val metadata = download.metadata
                PlayerSurface(
                    stream = StreamItem(
                        url = download.uri,
                        type = "hls",
                        quality = "Downloaded",
                        audio = metadata.category,
                        referer = metadata.referer,
                        isActive = true,
                        width = null,
                        height = null,
                        headers = metadata.headers,
                    ),
                    subtitles = EpisodeDownloads.localSubtitles(context, metadata),
                    skip = null,
                    seriesTitle = metadata.seriesTitle,
                    episodeTitle = metadata.episodeTitle?.takeIf(String::isNotBlank)
                        ?: "Episode ${metadata.episodeNumber}",
                    artworkUrl = metadata.artworkUrl,
                    animeId = metadata.anilistId,
                    provider = metadata.provider,
                    category = metadata.category,
                    episode = metadata.episodeNumber,
                    onEnded = {},
                    onNextEpisode = {},
                    onError = { message, _, _ -> playbackError = message },
                    modifier = Modifier.fillMaxSize(),
                    hasNextEpisode = false,
                    hasPreviousEpisode = false,
                    focusPlayerOnStart = true,
                    isFullscreen = true,
                    notificationRoute = Routes.download(download.id),
                )
            }
        }

        IconButton(
            onClick = leave,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .focusHighlight(CircleShape),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun DownloadMessage(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                message,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
