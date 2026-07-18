package com.miruronative.ui.watch

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.media3.common.ViewProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.miruronative.R
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.StreamItem
import com.miruronative.data.model.SubtitleItem
import com.miruronative.data.settings.CaptionEdgeStyle
import com.miruronative.data.settings.CaptionStyle
import com.miruronative.data.settings.DefaultQuality
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.playback.PlaybackService
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.rememberScreenReaderActive
import com.miruronative.ui.components.CaptionAppearanceDialog
import com.miruronative.ui.nav.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Same as media3's MediaRouteButtonViewProvider, but inflates the MediaRouteButton with an
 * AppCompat-derived theme. The activity keeps its framework theme, and MediaRouteButton (and the
 * route chooser/controller dialogs it opens) crash with "background can not be translucent: #0"
 * without AppCompat theme attributes — so both the button and its dialogs get a wrapped context.
 */
@UnstableApi
private class ThemedMediaRouteButtonViewProvider : ViewProvider {
    override fun getView(parent: ViewGroup): ListenableFuture<View> {
        val themedContext = ContextThemeWrapper(parent.context, R.style.Theme_MiruroNative_MediaRouter)
        val button = LayoutInflater.from(themedContext)
            .inflate(androidx.media3.cast.R.layout.media_route_button_view, parent, false) as MediaRouteButton
        button.setDialogFactory(ThemedMediaRouteDialogFactory())
        return Futures.transform(
            MediaRouteButtonFactory.setUpMediaRouteButton(parent.context, button),
            { button as View },
            MoreExecutors.directExecutor(),
        )
    }
}

/**
 * The route chooser/controller dialog fragments create their dialogs from the hosting activity's
 * context; wrap it with the AppCompat-derived theme the same way as the button. Named (non-private)
 * fragment classes so the FragmentManager can re-instantiate them after configuration changes.
 */
internal class ThemedMediaRouteChooserDialogFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?): MediaRouteChooserDialog =
        super.onCreateChooserDialog(
            ContextThemeWrapper(context, R.style.Theme_MiruroNative_MediaRouter),
            savedInstanceState,
        )
}

internal class ThemedMediaRouteControllerDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?): MediaRouteControllerDialog =
        super.onCreateControllerDialog(
            ContextThemeWrapper(context, R.style.Theme_MiruroNative_MediaRouter),
            savedInstanceState,
        )
}

private class ThemedMediaRouteDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment =
        ThemedMediaRouteChooserDialogFragment()

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment =
        ThemedMediaRouteControllerDialogFragment()
}

/** Makes PlayerView's own previous/next buttons navigate episodes, not its one-item playlist. */
@OptIn(UnstableApi::class)
private class EpisodeControlPlayer(
    player: Player,
    private val hasNextEpisode: Boolean,
    private val hasPreviousEpisode: Boolean,
    private val onNextEpisode: () -> Unit,
    private val onPreviousEpisode: () -> Unit,
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .removeAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNextEpisode)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextEpisode)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPreviousEpisode)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousEpisode)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> hasNextEpisode
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> hasPreviousEpisode
        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = hasNextEpisode
    override fun hasPreviousMediaItem(): Boolean = hasPreviousEpisode

    override fun seekToNext() {
        if (hasNextEpisode) onNextEpisode()
    }

    override fun seekToNextMediaItem() {
        if (hasNextEpisode) onNextEpisode()
    }

    override fun seekToPrevious() {
        if (hasPreviousEpisode) onPreviousEpisode()
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousEpisode) onPreviousEpisode()
    }
}

/** Media3 player surface backed by [PlaybackService] for PiP and system media controls. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    stream: StreamItem,
    qualityStreams: List<StreamItem> = listOf(stream),
    subtitles: List<SubtitleItem>,
    skip: SkipTimes?,
    seriesTitle: String,
    episodeTitle: String,
    artworkUrl: String?,
    animeId: Int,
    provider: String,
    category: String,
    episode: String,
    onEnded: () -> Unit,
    onNextEpisode: () -> Unit = onEnded,
    onError: (String, String, Long) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFullscreen: (() -> Unit)? = null,
    startPositionMs: Long = 0,
    onProgress: ((Long, Long) -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    hasNextEpisode: Boolean = true,
    hasPreviousEpisode: Boolean = true,
    focusPlayerOnStart: Boolean = true,
    isFullscreen: Boolean = false,
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    DisposableEffect(Unit) { onDispose { resetPlayerBrightness(context) } }
    val controllerFuture = remember(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        MediaController.Builder(context, token).buildAsync()
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val currentProvider by rememberUpdatedState(provider)
    val currentCategory by rememberUpdatedState(category)
    val currentOnError by rememberUpdatedState(onError)
    var activeStream by remember(stream.url) { mutableStateOf(stream) }
    var nextStartPositionMs by remember(stream.url) { mutableLongStateOf(startPositionMs) }
    var playbackIsPlaying by remember { mutableStateOf(false) }
    var tracksRevision by remember { mutableIntStateOf(0) }
    val nativeQualityStreams = remember(stream.url, qualityStreams) {
        (listOf(stream) + qualityStreams)
            .filterNot(StreamItem::isEmbed)
            .distinctBy(StreamItem::url)
    }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onSuccess {
                        DiagnosticsLog.event("PlayerSurface MediaController connected")
                        controller = it
                    }
                    .onFailure { DiagnosticsLog.throwable("PlayerSurface MediaController connection failed", it) }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }

    LaunchedEffect(controller) {
        if (controller == null) {
            delay(5_000)
            if (controller == null) {
                DiagnosticsLog.event("PlayerSurface controller still null after 5000ms")
            }
        }
    }

    LaunchedEffect(controller, stream.url) {
        controller?.let(::clearVideoSelection)
    }

    DisposableEffect(controller) {
        val activeController = controller
        if (activeController == null) {
            onDispose { }
        } else {
            playbackIsPlaying = activeController.isPlaying
            val listener = object : Player.Listener {
                private var audioPreferenceAppliedFor: String? = null

                override fun onPlaybackStateChanged(playbackState: Int) {
                    DiagnosticsLog.event(
                        "PlayerSurface playbackState=${playbackState.stateName()} " +
                            "mediaId=${activeController.currentMediaItem?.mediaId?.take(120) ?: "none"}",
                    )
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playbackIsPlaying = isPlaying
                    DiagnosticsLog.event("PlayerSurface isPlaying=$isPlaying")
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagnosticsLog.throwable("PlayerSurface player error code=${error.errorCodeName}", error)
                    currentOnError(
                        error.localizedMessage ?: "Playback failed",
                        activeController.currentMediaItem?.mediaId.orEmpty(),
                        activeController.currentPosition.coerceAtLeast(0L),
                    )
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    DiagnosticsLog.event("PlayerSurface tracks ${tracks.diagnosticSummary()}")
                    tracksRevision++
                    val mediaId = activeController.currentMediaItem?.mediaId ?: return
                    if (currentProvider != "reanime" || audioPreferenceAppliedFor == mediaId) return
                    if (applyReanimeAudioPreference(activeController, currentCategory)) {
                        audioPreferenceAppliedFor = mediaId
                    }
                }
            }
            DiagnosticsLog.event("PlayerSurface listener attached")
            activeController.addListener(listener)
            onDispose {
                onProgress?.invoke(
                    activeController.currentPosition.coerceAtLeast(0),
                    activeController.duration.coerceAtLeast(0),
                )
                activeController.removeListener(listener)
                DiagnosticsLog.event("PlayerSurface listener removed")
            }
        }
    }

    LaunchedEffect(controller, activeStream.url, subtitles) {
        val activeController = controller ?: return@LaunchedEffect
        if (activeController.currentMediaItem?.mediaId == activeStream.url) {
            DiagnosticsLog.event(
                "PlayerSurface media item already active " +
                    "host=${activeStream.host()} type=${activeStream.typeLabel()}",
            )
            return@LaunchedEffect
        }

        DiagnosticsLog.event(
            "PlayerSurface prepare stream type=${activeStream.typeLabel()} host=${activeStream.host()} " +
                "height=${activeStream.declaredVideoHeight() ?: "auto"} subtitles=${subtitles.size} " +
                "startMs=$nextStartPositionMs",
        )
        PlaybackService.configureRequestHeaders(activeStream.referer, activeStream.playlistKey)
        val watchRoute = Routes.watch(animeId, provider, category, episode)
        val metadata = MediaMetadata.Builder()
            .setTitle(episodeTitle)
            .setArtist(seriesTitle)
            .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .setExtras(Bundle().apply {
                putString(PlaybackService.EXTRA_WATCH_ROUTE, watchRoute)
            })
            .build()
        val item = MediaItem.Builder()
            .setMediaId(activeStream.url)
            .setUri(activeStream.url)
            .setMediaMetadata(metadata)
            .apply { if (activeStream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(
                subtitles.mapIndexed { index, subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setMimeType(mimeFor(subtitle.url))
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .apply {
                            // Sub streams carry the original audio, so surface the first
                            // subtitle track without requiring a manual selection. Dub
                            // viewers opt in via Settings ("Subtitles with dubbed audio").
                            if (index == 0 && (category != "dub" || SettingsStore.subtitlesWithDub.value)) {
                                setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            }
                        }
                        .build()
                },
            )
            .build()
        activeController.setMediaItem(item, nextStartPositionMs.coerceAtLeast(0))
        activeController.prepare()
        activeController.playWhenReady = true
        DiagnosticsLog.event("PlayerSurface prepare called playWhenReady=true")
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        val activeController = controller ?: return@LaunchedEffect
        while (isActive) {
            positionMs = activeController.currentPosition.coerceAtLeast(0)
            durationMs = activeController.duration.coerceAtLeast(0)
            if (activeController.isPlaying) {
                onProgress?.invoke(positionMs, durationMs)
            }
            delay(500)
        }
    }

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val mediaRouteButtonViewProvider = remember { ThemedMediaRouteButtonViewProvider() }
    var controllerVisible by remember { mutableStateOf(false) }
    var tvControlsVisible by remember { mutableStateOf(false) }
    var tvControlsInteraction by remember { mutableIntStateOf(0) }
    var lastAudibleVolume by remember { mutableStateOf(1f) }
    val tvPlayPauseFocus = remember { FocusRequester() }
    val tvPlayerFocus = remember { FocusRequester() }
    LaunchedEffect(tvControlsVisible, focusPlayerOnStart) {
        if (!tvControlsVisible || !focusPlayerOnStart) return@LaunchedEffect
        delay(32)
        runCatching { tvPlayPauseFocus.requestFocus() }
    }
    val screenReaderActive = rememberScreenReaderActive()
    // TalkBack users can't discover the hidden control row through a key press, so present it
    // as soon as the fullscreen player opens instead of waiting for the semantic reveal action.
    LaunchedEffect(screenReaderActive, focusPlayerOnStart, activeStream.url) {
        if (device.isTv && screenReaderActive && focusPlayerOnStart) {
            tvControlsVisible = true
        }
    }
    LaunchedEffect(tvControlsVisible, tvControlsInteraction, focusPlayerOnStart, screenReaderActive) {
        if (!focusPlayerOnStart) {
            tvControlsVisible = false
            return@LaunchedEffect
        }
        if (!tvControlsVisible) return@LaunchedEffect
        // TalkBack users navigate slowly and can't reopen the controls with a key press
        // (the screen reader consumes the D-pad), so never auto-hide under a screen reader.
        if (screenReaderActive) return@LaunchedEffect
        delay(8_000)
        tvControlsVisible = false
        runCatching { tvPlayerFocus.requestFocus() }
    }
    LaunchedEffect(activeStream.url, playerView, device.isTv, focusPlayerOnStart, screenReaderActive) {
        // Under a screen reader the control row is auto-shown and focused; reclaiming focus for
        // the player surface would pull TalkBack away from the controls it just landed on.
        if (device.isTv && focusPlayerOnStart && playerView != null && !screenReaderActive) {
            delay(32)
            runCatching { tvPlayerFocus.requestFocus() }
        }
    }
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val currentHasNext by rememberUpdatedState(hasNextEpisode)
    val currentHasPrevious by rememberUpdatedState(hasPreviousEpisode)
    val canGoPrevious = hasPreviousEpisode && onPreviousEpisode != null
    val playerControls = remember(controller, hasNextEpisode, canGoPrevious) {
        controller?.let { activeController ->
            EpisodeControlPlayer(
                player = activeController,
                hasNextEpisode = hasNextEpisode,
                hasPreviousEpisode = canGoPrevious,
                onNextEpisode = { currentOnNextEpisode() },
                onPreviousEpisode = { currentOnPreviousEpisode?.invoke() },
            )
        }
    }

    // Bridges notification, remote, and hardware media-key commands into episode resolution.
    DisposableEffect(Unit) {
        DiagnosticsLog.event("PlayerSurface episode navigator registered hasPrev=$hasPreviousEpisode hasNext=$hasNextEpisode")
        val navigator: (Int) -> Unit = { direction ->
            DiagnosticsLog.event("PlayerSurface episode navigator direction=$direction")
            when {
                direction > 0 && currentHasNext -> currentOnNextEpisode()
                direction < 0 && currentHasPrevious -> currentOnPreviousEpisode?.invoke()
            }
        }
        PlaybackService.episodeNavigator = navigator
        onDispose {
            if (PlaybackService.episodeNavigator === navigator) {
                PlaybackService.episodeNavigator = null
            }
            DiagnosticsLog.event("PlayerSurface episode navigator cleared")
        }
    }
    var settingsExpanded by remember { mutableStateOf(false) }
    var captionAppearanceVisible by remember { mutableStateOf(false) }
    // Phone draws its own Compose controls (Media3's controller is disabled below); TV keeps
    // TvPlayerControls. Visible on load, then auto-hidden a few seconds into playback.
    var phoneControlsVisible by remember { mutableStateOf(true) }
    var phoneControlsInteraction by remember { mutableIntStateOf(0) }
    var contentScale by remember { mutableStateOf(PlayerContentScale.FIT) }
    val trackNameProvider = remember(context) { DefaultTrackNameProvider(context.resources) }
    LaunchedEffect(phoneControlsVisible, phoneControlsInteraction, playbackIsPlaying, device.isTv) {
        if (device.isTv || !phoneControlsVisible || !playbackIsPlaying) return@LaunchedEffect
        delay(4_000)
        phoneControlsVisible = false
    }
    val captionStyle by SettingsStore.captionStyle.collectAsState()
    var pinnedVideoHeight by remember(controller, stream.url) { mutableStateOf<Int?>(null) }
    fun changeVideoHeight(activeController: MediaController, height: Int?): Boolean {
        val applied = when {
            height == null -> {
                clearVideoSelection(activeController)
                if (activeStream.url != stream.url) {
                    nextStartPositionMs = activeController.currentPosition.coerceAtLeast(0)
                    activeStream = stream
                }
                DiagnosticsLog.event("PlayerSurface quality selection mode=auto")
                true
            }
            activeController.hasVideoHeight(height) -> applyVideoHeight(activeController, height)
            else -> {
                val source = nativeQualityStreams.firstOrNull { it.declaredVideoHeight() == height }
                if (source == null) {
                    DiagnosticsLog.event("PlayerSurface quality selection rejected height=$height unavailable")
                    false
                } else {
                    clearVideoSelection(activeController)
                    nextStartPositionMs = activeController.currentPosition.coerceAtLeast(0)
                    activeStream = source
                    DiagnosticsLog.event(
                        "PlayerSurface quality selection mode=manual height=$height " +
                            "source=${source.typeLabel()} host=${source.host()}",
                    )
                    true
                }
            }
        }
        if (applied) pinnedVideoHeight = height
        return applied
    }
    val defaultQuality by SettingsStore.defaultQuality.collectAsState()
    var defaultQualityApplied by remember(stream.url) { mutableStateOf(false) }
    LaunchedEffect(controller, tracksRevision, defaultQuality) {
        val activeController = controller ?: return@LaunchedEffect
        if (defaultQualityApplied || pinnedVideoHeight != null) return@LaunchedEffect
        if (defaultQuality == DefaultQuality.AUTO) return@LaunchedEffect
        // The controller is persistent across episodes, so its tracks may still describe the
        // previous media item; only apply once it is actually reporting this stream.
        if (activeController.currentMediaItem?.mediaId != activeStream.url) return@LaunchedEffect
        val heights = availableVideoHeights(activeController, nativeQualityStreams)
        val target = defaultQuality.pickHeight(heights) ?: return@LaunchedEffect
        defaultQualityApplied = true
        DiagnosticsLog.event(
            "PlayerSurface default quality=${defaultQuality.storedValue} target=${target}p heights=$heights",
        )
        changeVideoHeight(activeController, target)
    }
    var seekFlash by remember { mutableIntStateOf(0) } // -10 / +10, 0 = hidden
    var seekFlashTick by remember { mutableIntStateOf(0) }
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val introStartMs = skip?.introStart?.times(1000)?.toLong() ?: 0L
    val introEndMs = skip?.introEnd?.times(1000)?.toLong()
    val outroStartMs = skip?.outroStart?.times(1000)?.toLong()
    val outroEndMs = skip?.outroEnd?.times(1000)?.toLong()
    var introAutoSkipped by remember(activeStream.url, introStartMs, introEndMs) { mutableStateOf(false) }
    var outroAutoHandled by remember(activeStream.url, outroStartMs, outroEndMs) { mutableStateOf(false) }

    LaunchedEffect(seekFlashTick) {
        if (seekFlash != 0) {
            delay(650)
            seekFlash = 0
        }
    }

    LaunchedEffect(
        autoSkipIntroOutro,
        autoplay,
        controller,
        positionMs,
        introStartMs,
        introEndMs,
        outroStartMs,
        outroEndMs,
    ) {
        val activeController = controller ?: return@LaunchedEffect
        if (!autoSkipIntroOutro || !activeController.isPlaying) return@LaunchedEffect

        if (!introAutoSkipped && isInSkipWindow(positionMs, introStartMs, introEndMs)) {
            introAutoSkipped = true
            activeController.seekTo(introEndMs ?: return@LaunchedEffect)
            return@LaunchedEffect
        }

        if (
            autoplay &&
            !outroAutoHandled &&
            isInSkipWindow(positionMs, outroStartMs, outroEndMs)
        ) {
            outroAutoHandled = true
            onNextEpisode()
        }
    }

    val remoteModifier = if (device.isTv && focusPlayerOnStart) {
        Modifier
            .focusRequester(tvPlayerFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!opensTvPlayerControls(event.key)) return@onPreviewKeyEvent false
                tvControlsInteraction++
                if (!tvControlsVisible) {
                    DiagnosticsLog.event("PlayerSurface TV controls opened key=${event.key}")
                    tvControlsVisible = true
                    true
                } else {
                    false
                }
            }
            // Screen readers swallow the D-pad, so the key handler above never fires under
            // TalkBack; this semantic action is the accessible way to reveal the controls.
            .semantics {
                contentDescription = "Video player"
                onClick(label = "Show player controls") {
                    tvControlsInteraction++
                    tvControlsVisible = true
                    true
                }
            }
            .focusable()
    } else {
        Modifier
    }
    Box(modifier.then(remoteModifier)) {
        AndroidView(
            factory = { ctx ->
                DiagnosticsLog.event("PlayerSurface AndroidView factory create PlayerView")
                PlayerView(ctx).apply {
                    player = playerControls
                    setMediaRouteButtonViewProvider(mediaRouteButtonViewProvider)
                    // Phone and TV both draw their own controls (Compose bar / TvPlayerControls),
                    // so Media3's built-in controller is off everywhere.
                    useController = false
                    keepScreenOn = true
                    isFocusable = !device.isTv
                    isFocusableInTouchMode = !device.isTv
                    setShowSubtitleButton(true)
                    // EpisodeControlPlayer maps these to app navigation; the playlist stays
                    // single-item because each episode resolves against multiple providers.
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    controllerShowTimeoutMs = if (device.isTv) 6_000 else 5_000
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controllerVisible = visibility == View.VISIBLE
                            if (visibility == View.VISIBLE && device.isTv) {
                                post {
                                    findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                                        ?.requestFocus()
                                }
                            } else if (visibility != View.VISIBLE) {
                                // TV: the controller's buttons held window focus; when they go
                                // GONE focus is cleared entirely and every remote key except
                                // Back lands nowhere. Reclaim focus so D-pad/OK can resummon
                                // the controller (and reach play/pause).
                                if (device.isTv) post { requestFocus() }
                            }
                        },
                    )
                    if (onToggleFullscreen != null) {
                        setFullscreenButtonClickListener { onToggleFullscreen() }
                    }
                    bindUnifiedSettingsButton { settingsExpanded = true }
                    playerView = this
                }
            },
            update = {
                it.player = playerControls
                it.isFocusable = !device.isTv
                it.isFocusableInTouchMode = !device.isTv
                if (device.isTv) it.clearFocus()
                it.applyCaptionStyle(captionStyle)
                // Deliberately NOT re-setting the media route button provider here: every
                // setMediaRouteButtonViewProvider call inflates a fresh button, so repeated
                // update passes stack duplicate cast icons. The factory sets it once.
                it.bindUnifiedSettingsButton { settingsExpanded = true }
                DiagnosticsLog.event(
                    "PlayerSurface AndroidView update controller=${controller != null} " +
                        "size=${it.width}x${it.height} shown=${it.isShown}",
                )
            },
            onRelease = {
                it.player = null
                playerView = null
                DiagnosticsLog.event("PlayerSurface AndroidView release size=${it.width}x${it.height}")
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Phone controls, hidden: the gesture layer owns the surface — tap shows the controls, a
        // vertical drag on the left half scrubs brightness / right half volume, a double tap seeks
        // ±10 s. (TV uses TvPlayerControls instead.)
        if (controller != null && !device.isTv && !phoneControlsVisible) {
            PlayerGestureControls(
                onTap = {
                    phoneControlsVisible = true
                    phoneControlsInteraction++
                },
                onDoubleTap = { isRightHalf ->
                    val active = controller ?: return@PlayerGestureControls
                    if (isRightHalf) {
                        active.seekForward()
                        seekFlash = +10
                    } else {
                        active.seekBack()
                        seekFlash = -10
                    }
                    seekFlashTick++
                },
            )
        }

        // Phone controls, shown: the shared control bar (identical to the embed player) over a
        // full-screen scrim that hides it again on a tap in empty space.
        if (controller != null && !device.isTv && phoneControlsVisible) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { phoneControlsVisible = false } },
            )
            PlayerControlsScaffold(
                isPlaying = playbackIsPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                hasPrevious = canGoPrevious,
                hasNext = hasNextEpisode,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = {
                    controller?.seekBack()
                    phoneControlsInteraction++
                },
                onPlayPause = {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    phoneControlsInteraction++
                },
                onForward = {
                    controller?.seekForward()
                    phoneControlsInteraction++
                },
                onNext = { currentOnNextEpisode() },
                onSeek = { target ->
                    controller?.seekTo(target)
                    phoneControlsInteraction++
                },
                onInteract = { phoneControlsInteraction++ },
            ) {
                PlayerControlIconButton(
                    "Subtitles",
                    Icons.Default.ClosedCaption,
                    onClick = {
                        controller?.let { toggleSubtitles(it, trackNameProvider) }
                        phoneControlsInteraction++
                    },
                )
                CastButton(Modifier.size(48.dp))
                PlayerControlIconButton(
                    if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                    if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    onClick = {
                        onToggleFullscreen?.invoke()
                        phoneControlsInteraction++
                    },
                )
                PlayerControlIconButton(
                    "Settings",
                    Icons.Default.Settings,
                    onClick = {
                        settingsExpanded = true
                        phoneControlsInteraction++
                    },
                )
            }
        }

        if (seekFlash != 0) {
            Text(
                if (seekFlash > 0) "+10 s" else "−10 s",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(if (seekFlash > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        if (settingsExpanded && controller != null) {
            val activeController = controller!!
            val trackHeights = availableVideoHeights(activeController, nativeQualityStreams)
            val qualityOptions = buildList {
                add(PlayerQualityOption("Auto", pinnedVideoHeight == null) { changeVideoHeight(activeController, null) })
                trackHeights.forEach { height ->
                    add(PlayerQualityOption("${height}p", pinnedVideoHeight == height) { changeVideoHeight(activeController, height) })
                }
            }
            val audioTracks = trackOptions(activeController, trackNameProvider, C.TRACK_TYPE_AUDIO)
            val hasAudioOverride = activeController.hasTrackOverride(C.TRACK_TYPE_AUDIO)
            val audioOptions = if (audioTracks.size > 1) {
                buildList {
                    add(PlayerQualityOption("Auto", !hasAudioOverride) { applyAudioTrack(activeController, null) })
                    audioTracks.forEach { track ->
                        add(PlayerQualityOption(track.name, hasAudioOverride && track.selected) {
                            applyAudioTrack(activeController, track)
                        })
                    }
                }
            } else {
                emptyList()
            }
            val subtitleTracks = trackOptions(activeController, trackNameProvider, C.TRACK_TYPE_TEXT)
            val subtitleOptions = if (subtitleTracks.isNotEmpty()) {
                buildList {
                    add(PlayerQualityOption("Off", subtitleTracks.none { it.selected }) {
                        applyTextTrack(activeController, null)
                    })
                    subtitleTracks.forEach { track ->
                        add(PlayerQualityOption(track.name, track.selected) { applyTextTrack(activeController, track) })
                    }
                }
            } else {
                emptyList()
            }
            PlayerSettingsSheet(
                onDismiss = { settingsExpanded = false },
                autoplay = autoplay,
                onAutoplayChange = SettingsStore::setAutoplay,
                speed = activeController.playbackParameters.speed,
                onSpeedChange = { activeController.setPlaybackSpeed(it) },
                qualityOptions = qualityOptions,
                subtitleOptions = subtitleOptions,
                audioOptions = audioOptions,
                contentScale = contentScale,
                onContentScaleChange = { scale ->
                    contentScale = scale
                    playerView?.resizeMode = when (scale) {
                        PlayerContentScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerContentScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PlayerContentScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                onCaptionAppearance = {
                    settingsExpanded = false
                    captionAppearanceVisible = true
                },
                autoSkip = autoSkipIntroOutro,
                onAutoSkipChange = SettingsStore::setAutoSkipIntroOutro,
            )
        }

        if (captionAppearanceVisible) {
            CaptionAppearanceDialog(onDismiss = { captionAppearanceVisible = false })
        }

        if (controller == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        if (device.isTv && focusPlayerOnStart && tvControlsVisible && controller != null) {
            TvPlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = playbackIsPlaying,
                isMuted = controller?.volume?.let { it <= 0.001f } == true,
                hasPrevious = canGoPrevious,
                hasNext = hasNextEpisode,
                playPauseFocusRequester = tvPlayPauseFocus,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = {
                    DiagnosticsLog.event("PlayerSurface TV control rewind")
                    controller?.seekBack()
                },
                onPlayPause = {
                    DiagnosticsLog.event("PlayerSurface TV control playPause")
                    controller?.let { active -> if (active.isPlaying) active.pause() else active.play() }
                },
                onForward = {
                    DiagnosticsLog.event("PlayerSurface TV control forward")
                    controller?.seekForward()
                },
                onNext = currentOnNextEpisode,
                onVolumeDown = {
                    DiagnosticsLog.event("PlayerSurface TV control volumeDown")
                    controller?.let { active ->
                        active.volume = (active.volume - 0.1f).coerceAtLeast(0f)
                        if (active.volume > 0f) lastAudibleVolume = active.volume
                    }
                },
                onToggleMute = {
                    DiagnosticsLog.event("PlayerSurface TV control toggleMute")
                    controller?.let { active ->
                        if (active.volume > 0.001f) {
                            lastAudibleVolume = active.volume
                            active.volume = 0f
                        } else {
                            active.volume = lastAudibleVolume.coerceAtLeast(0.1f)
                        }
                    }
                },
                onVolumeUp = {
                    DiagnosticsLog.event("PlayerSurface TV control volumeUp")
                    controller?.let { active ->
                        active.volume = (active.volume + 0.1f).coerceAtMost(1f)
                        lastAudibleVolume = active.volume
                    }
                },
                onSettings = {
                    DiagnosticsLog.event("PlayerSurface TV control settings")
                    settingsExpanded = true
                },
                onFullscreen = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        val action: Pair<String, () -> Unit>? = when {
            introEndMs != null && positionMs in introStartMs..introEndMs ->
                "Skip Intro" to { controller?.seekTo(introEndMs); Unit }
            outroStartMs != null && outroEndMs != null && positionMs in outroStartMs..outroEndMs ->
                "Next Episode" to onNextEpisode
            else -> null
        }
        LaunchedEffect(action?.first, playerView, device.isTv, focusPlayerOnStart) {
            if (device.isTv && focusPlayerOnStart) {
                // Compose may focus a newly inserted skip/next action before PlayerView can
                // reclaim focus. Return remote input to the player once this frame settles.
                delay(32)
                runCatching { tvPlayerFocus.requestFocus() }
            }
        }
        action?.let { (label, onClick) ->
            val controlsVisible = if (device.isTv) tvControlsVisible else phoneControlsVisible
            val actionModifier = if (controlsVisible) {
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
            } else {
                Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 24.dp)
            }
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = actionModifier,
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Quick subtitle on/off for the control bar's CC button; full track choice lives in the sheet. */
@OptIn(UnstableApi::class)
private fun toggleSubtitles(controller: MediaController, trackNameProvider: DefaultTrackNameProvider) {
    val tracks = trackOptions(controller, trackNameProvider, C.TRACK_TYPE_TEXT)
    if (tracks.isEmpty()) return
    if (tracks.any { it.selected }) applyTextTrack(controller, null)
    else applyTextTrack(controller, tracks.first())
}

/**
 * The Cast button as a Compose element. Media3's own controller is off, so we inflate the
 * MediaRouteButton ourselves (with the AppCompat-derived theme its dialogs require) and place it
 * in the custom control bar.
 */
@OptIn(UnstableApi::class)
@Composable
private fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val themed = ContextThemeWrapper(ctx, R.style.Theme_MiruroNative_MediaRouter)
            val button = LayoutInflater.from(themed)
                .inflate(androidx.media3.cast.R.layout.media_route_button_view, null, false) as MediaRouteButton
            button.setDialogFactory(ThemedMediaRouteDialogFactory())
            runCatching { MediaRouteButtonFactory.setUpMediaRouteButton(ctx, button) }
            button
        },
    )
}

/** Heights offered by the loaded tracks plus any alternate per-quality source streams. */
private fun availableVideoHeights(
    controller: MediaController,
    qualityStreams: List<StreamItem>,
): List<Int> = (
    controller.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        .flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported)
                .map { group.getTrackFormat(it).height }
        }
        .filter { it > 0 } + qualityStreams.mapNotNull(StreamItem::declaredVideoHeight)
    ).distinct().sortedDescending()

private fun applyVideoHeight(controller: MediaController, height: Int?): Boolean {
    val builder = controller.videoSelectionBuilder()
    if (height == null) {
        controller.trackSelectionParameters = builder.build()
        DiagnosticsLog.event("PlayerSurface quality selection mode=auto")
        return true
    }

    val option = controller.currentTracks.groups.asSequence()
        .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        .flatMap { group ->
            (0 until group.length).asSequence()
                .filter(group::isTrackSupported)
                .map { index -> group to index }
        }
        .firstOrNull { (group, index) -> group.getTrackFormat(index).height == height }
    if (option == null) {
        DiagnosticsLog.event("PlayerSurface quality selection rejected height=$height unavailable")
        return false
    }

    val (group, index) = option
    builder.setOverrideForType(TrackSelectionOverride(group.getMediaTrackGroup(), listOf(index)))
    controller.trackSelectionParameters = builder.build()
    DiagnosticsLog.event(
        "PlayerSurface quality selection mode=manual height=$height index=$index tracks=${group.length}",
    )
    return true
}

private fun clearVideoSelection(controller: MediaController) {
    controller.trackSelectionParameters = controller.videoSelectionBuilder().build()
}

private fun MediaController.videoSelectionBuilder(): TrackSelectionParameters.Builder =
    trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .clearVideoSizeConstraints()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)

private fun MediaController.hasVideoHeight(height: Int): Boolean = currentTracks.groups.any { group ->
    group.type == C.TRACK_TYPE_VIDEO && group.isSupported &&
        (0 until group.length).any { index ->
            group.isTrackSupported(index) && group.getTrackFormat(index).height == height
        }
}

private fun MediaController.hasTrackOverride(trackType: Int): Boolean =
    trackSelectionParameters.overrides.values.any { it.type == trackType }

@OptIn(UnstableApi::class)
private fun trackOptions(
    controller: MediaController,
    trackNameProvider: DefaultTrackNameProvider,
    trackType: Int,
): List<TrackOption> = controller.currentTracks.groups
    .filter { it.type == trackType && it.isSupported }
    .flatMap { group ->
        (0 until group.length)
            .filter { group.isTrackSupported(it) }
            .map { index ->
                TrackOption(
                    trackGroup = group.getMediaTrackGroup(),
                    trackIndex = index,
                    name = trackNameProvider.getTrackName(group.getTrackFormat(index)),
                    selected = group.isTrackSelected(index),
                )
            }
    }

private fun applyAudioTrack(controller: MediaController, option: TrackOption?) {
    val builder = controller.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    if (option != null) {
        builder.setOverrideForType(TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex)))
    }
    controller.trackSelectionParameters = builder.build()
    DiagnosticsLog.event(
        if (option == null) {
            "PlayerSurface audio selection mode=auto"
        } else {
            "PlayerSurface audio selection mode=manual name=${option.name.take(80)}"
        },
    )
}

private fun applyReanimeAudioPreference(controller: MediaController, category: String): Boolean {
    val wantsDub = category.equals("dub", ignoreCase = true)
    val options = controller.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { index ->
                    val format = group.getTrackFormat(index)
                    TrackOption(
                        trackGroup = group.getMediaTrackGroup(),
                        trackIndex = index,
                        name = listOfNotNull(format.label, format.language).joinToString(" ").ifBlank { "Audio" },
                        selected = group.isTrackSelected(index),
                    )
                }
        }
    if (options.size < 2) return true
    val selected = options.firstOrNull { it.selected }
    val preferred = options.minByOrNull { reanimeAudioRank(it.name, wantsDub) }
        ?.takeIf { reanimeAudioRank(it.name, wantsDub) < 50 }
        ?: return true
    if (selected?.trackGroup == preferred.trackGroup && selected.trackIndex == preferred.trackIndex) return true
    applyAudioTrack(controller, preferred)
    DiagnosticsLog.event("PlayerSurface ReAnime audio selected category=$category name=${preferred.name.take(80)}")
    return true
}

private fun reanimeAudioRank(name: String, wantsDub: Boolean): Int {
    val lower = name.lowercase()
    return if (wantsDub) {
        when {
            lower.contains("english") || lower.contains(" eng") || lower == "en" -> 0
            lower.contains("dub") -> 5
            else -> 100
        }
    } else {
        when {
            lower.contains("japanese") || lower.contains(" jpn") || lower.contains(" ja") || lower == "ja" -> 0
            lower.contains("native") -> 5
            else -> 100
        }
    }
}

private fun applyTextTrack(controller: MediaController, option: TrackOption?) {
    val builder = controller.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
    if (option != null) {
        builder.setOverrideForType(TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex)))
    }
    controller.trackSelectionParameters = builder.build()
    DiagnosticsLog.event(
        if (option == null) {
            "PlayerSurface subtitle selection mode=off"
        } else {
            "PlayerSurface subtitle selection mode=manual name=${option.name.take(80)}"
        },
    )
}

/**
 * Overrides the style PlayerView installs in its constructor. Without this, [SubtitleView] falls
 * back to [CaptionStyleCompat.DEFAULT] — white on *fully opaque* black — for everyone who hasn't
 * turned on captions in the system's Accessibility settings, which paints a solid box over the
 * picture.
 */
@OptIn(UnstableApi::class)
private fun PlayerView.applyCaptionStyle(style: CaptionStyle) {
    val view = subtitleView ?: return
    view.setStyle(
        CaptionStyleCompat(
            style.textArgb,
            style.backgroundArgb,
            android.graphics.Color.TRANSPARENT, // window: the box behind the whole line
            style.edgeStyle.toMedia3EdgeType(),
            android.graphics.Color.BLACK,
            null,
        ),
    )
    view.setFractionalTextSize(
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.textScalePercent / 100f,
    )
}

@OptIn(UnstableApi::class)
private fun CaptionEdgeStyle.toMedia3EdgeType(): Int = when (this) {
    CaptionEdgeStyle.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    CaptionEdgeStyle.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    CaptionEdgeStyle.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
}

@OptIn(UnstableApi::class)
private fun PlayerView.bindUnifiedSettingsButton(onClick: () -> Unit) {
    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
        showController()
        onClick()
    }
}

private fun isInSkipWindow(positionMs: Long, startMs: Long?, endMs: Long?): Boolean {
    val start = startMs ?: 0L
    val end = endMs ?: return false
    return end > start && positionMs in start until end
}

internal fun Float.formatPlaybackSpeed(): String = if (this % 1f == 0f) {
    "${toInt()}x"
} else {
    "${this}x"
}

private data class TrackOption(
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val name: String,
    val selected: Boolean,
)

internal val PlaybackSpeeds = listOf(
    0.25f,
    0.5f,
    0.75f,
    0.9f,
    0.95f,
    1f,
    1.05f,
    1.1f,
    1.15f,
    1.2f,
    1.25f,
    1.3f,
    1.5f,
    1.75f,
    2f,
)

private fun Int.stateName(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}

private fun StreamItem.typeLabel(): String = when {
    isEmbed -> "embed"
    isHls -> "hls"
    else -> "direct"
}

private fun StreamItem.host(): String =
    runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"

private fun StreamItem.declaredVideoHeight(): Int? = height ?: declaredVideoHeight(quality)

internal fun declaredVideoHeight(label: String?): Int? = label
    ?.let { Regex("""(?<!\d)(\d{3,4})p\b""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
    ?.toIntOrNull()
    ?.takeIf { it in 144..4320 }

private fun androidx.media3.common.Tracks.diagnosticSummary(): String = groups
    .filter { it.isSupported }
    .joinToString(separator = ";", limit = 12, truncated = "…") { group ->
        val type = when (group.type) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            else -> "type${group.type}"
        }
        val options = (0 until group.length)
            .filter(group::isTrackSupported)
            .joinToString(separator = ",", limit = 12, truncated = "…") { index ->
                val format = group.getTrackFormat(index)
                val label = when (group.type) {
                    C.TRACK_TYPE_VIDEO -> format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "unknown"
                    else -> listOfNotNull(format.label, format.language).joinToString("/").ifBlank { "unknown" }
                }
                label + if (group.isTrackSelected(index)) "*" else ""
            }
        "$type=$options"
    }

private fun mimeFor(url: String): String = when {
    url.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    url.contains(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.TEXT_VTT
}
