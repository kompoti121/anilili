package com.miruronative.ui.watch

import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import com.miruronative.playback.PlaybackService
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Media3 player surface backed by [PlaybackService] for PiP and system media controls. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    stream: StreamItem,
    subtitles: List<SubtitleItem>,
    skip: SkipTimes?,
    seriesTitle: String,
    episodeTitle: String,
    artworkUrl: String?,
    onEnded: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFullscreen: (() -> Unit)? = null,
    startPositionMs: Long = 0,
    onProgress: ((Long, Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val controllerFuture = remember(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        MediaController.Builder(context, token).buildAsync()
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            { controller = runCatching { controllerFuture.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }

    DisposableEffect(controller) {
        val activeController = controller
        if (activeController == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError(error.localizedMessage ?: "Playback failed")
                }
            }
            activeController.addListener(listener)
            onDispose {
                onProgress?.invoke(
                    activeController.currentPosition.coerceAtLeast(0),
                    activeController.duration.coerceAtLeast(0),
                )
                activeController.removeListener(listener)
                activeController.stop()
                activeController.clearMediaItems()
            }
        }
    }

    LaunchedEffect(controller, stream.url, subtitles) {
        val activeController = controller ?: return@LaunchedEffect
        if (activeController.currentMediaItem?.mediaId == stream.url) return@LaunchedEffect

        PlaybackService.configureRequestHeaders(stream.referer)
        val metadata = MediaMetadata.Builder()
            .setTitle(episodeTitle)
            .setArtist(seriesTitle)
            .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = MediaItem.Builder()
            .setMediaId(stream.url)
            .setUri(stream.url)
            .setMediaMetadata(metadata)
            .apply { if (stream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(
                subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setMimeType(mimeFor(subtitle.url))
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .build()
                },
            )
            .build()
        activeController.setMediaItem(item, startPositionMs.coerceAtLeast(0))
        activeController.prepare()
        activeController.playWhenReady = true
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        val activeController = controller ?: return@LaunchedEffect
        while (isActive) {
            positionMs = activeController.currentPosition.coerceAtLeast(0)
            if (activeController.isPlaying) {
                onProgress?.invoke(positionMs, activeController.duration.coerceAtLeast(0))
            }
            delay(500)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controller
                    useController = true
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    controllerShowTimeoutMs = if (device.isTv) 6_000 else 5_000
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    if (onToggleFullscreen != null) {
                        setFullscreenButtonClickListener { onToggleFullscreen() }
                    }
                    if (device.isTv) post { requestFocus() }
                }
            },
            update = { it.player = controller },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (controller == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        val introStartMs = ((skip?.introStart ?: 0.0) * 1000).toLong()
        val introEndMs = skip?.introEnd?.times(1000)?.toLong()
        val outroStartMs = skip?.outroStart?.times(1000)?.toLong()
        val outroEndMs = skip?.outroEnd?.times(1000)?.toLong()
        val action: Pair<String, () -> Unit>? = when {
            introEndMs != null && positionMs in introStartMs..introEndMs ->
                "Skip Intro" to { controller?.seekTo(introEndMs); Unit }
            outroStartMs != null && outroEndMs != null && positionMs in outroStartMs..outroEndMs ->
                "Next Episode" to onEnded
            else -> null
        }
        action?.let { (label, onClick) ->
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                Text(label)
            }
        }
    }
}

private fun mimeFor(url: String): String = when {
    url.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    url.contains(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.TEXT_VTT
}
