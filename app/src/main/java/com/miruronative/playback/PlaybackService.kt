package com.miruronative.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.MainActivity
import com.miruronative.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide playback state used by the activity to decide when PiP is appropriate. */
object PlaybackStatus {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    internal fun update(playing: Boolean) {
        _isPlaying.value = playing
    }
}

/**
 * Owns the app playback player so playback survives activity backgrounding and exposes Android
 * notification, lock-screen, headset, Bluetooth, and Cast controls through a MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var session: MediaSession
    private lateinit var httpFactory: HttpDataSource.Factory

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.event("PlaybackService.onCreate")
        val playerUserAgent = runCatching { WebSettings.getDefaultUserAgent(this).replace("; wv", "") }
            .getOrDefault(FALLBACK_PLAYER_USER_AGENT)
        activeUserAgent = playerUserAgent
        val defaultHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(playerUserAgent)
            .setAllowCrossProtocolRedirects(true)
        val cronetEngine = CronetUtil.buildCronetEngine(this, null, true)
        httpFactory = if (cronetEngine != null) {
            DiagnosticsLog.event("PlaybackService HTTP transport=Cronet provider=${cronetEngine.versionString}")
            CronetDataSource.Factory(cronetEngine, Runnable::run)
                .setUserAgent(playerUserAgent)
        } else {
            DiagnosticsLog.event("PlaybackService HTTP transport=DefaultHttpDataSource")
            defaultHttpFactory
        }
        activeHttpFactory = httpFactory
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val playbackDataSource = DataSource.Factory {
            FlixcloudPlaylistDataSource(cacheDataSource.createDataSource()) { activePlaylistKey }
        }

        // Fire TV sticks have ~1GB of RAM and were getting killed by the low-memory killer mid
        // episode: ExoPlayer's default ~50s buffer of 1080p HLS plus the app's WebViews exceed
        // what the OS tolerates. A 30s cap keeps the buffer's memory (and network bursts)
        // proportionate to the device; phones keep the defaults.
        val isTvDevice = (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val loadControl = if (isTvDevice) {
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
                .build()
        } else {
            androidx.media3.exoplayer.DefaultLoadControl.Builder().build()
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSource))
            .setRenderersFactory(SubtitleDelayRenderersFactory(this))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        castPlayer = CastPlayer(CastContext.getSharedInstance(this))
        activePlayer = player
        player.addListener(playbackListener(player))
        castPlayer.addListener(playbackListener(castPlayer))

        val localEpisodeAwarePlayer = episodeAwarePlayer(player)
        val castEpisodeAwarePlayer = episodeAwarePlayer(castPlayer)

        // Brand the media notification: Media3's provider handles layout, actions, and artwork
        // (loading MediaMetadata.artworkUri itself); only the small icon needs overriding.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(com.miruronative.R.drawable.ic_notification) },
        )
        session = MediaSession.Builder(this, localEpisodeAwarePlayer)
            .setSessionActivity(sessionActivity(null))
            .setCallback(object : MediaSession.Callback {
                // A paused-but-loaded session normally resumes on any KEYCODE_MEDIA_PLAY —
                // including ones a Bluetooth headset or car fires on reconnect, which made
                // paused audio restart in the background "by itself". While the app is
                // backgrounded after a lifecycle pause, ignore media-button play; the
                // notification's own play action arrives as a controller command (not a key
                // event), so deliberate resumes still work.
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    val isResumeKey = key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        key?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    if (isResumeKey && suppressMediaButtonResume && activePlayer?.isPlaying != true) {
                        DiagnosticsLog.event(
                            "PlaybackService ignored background media-button resume key=${key?.keyCode}",
                        )
                        return true
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
            .build()
        session.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(CommandButton.ICON_REWIND)
                    .setDisplayName("Rewind 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
                    .setDisplayName("Forward 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .build(),
            ),
        )

        // Media3 1.9 introduced CastPlayer.Builder.setLocalPlayer(), but that release also raised
        // minSdk to 23. Keep the same local/cast hand-off explicitly so Fire OS 5 (API 22) can use
        // the player without sacrificing casting on newer devices.
        castPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                switchPlaybackPlayer(player, castPlayer, castEpisodeAwarePlayer)
            }

            override fun onCastSessionUnavailable() {
                switchPlaybackPlayer(castPlayer, player, localEpisodeAwarePlayer)
            }
        })
    }

    private fun playbackListener(source: Player): Player.Listener =
        object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.event("PlaybackService player isPlaying=$isPlaying")
                        PlaybackStatus.update(isPlaying)
                        // Any playback that actually starts (notification play, in-app play)
                        // re-arms the media buttons; the suppression only covers the window
                        // between the lifecycle pause and the next deliberate start.
                        if (isPlaying) allowMediaButtonResume()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        DiagnosticsLog.event("PlaybackService player state=${playbackState.stateName()}")
                    }

                    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                        if (activePlayer !== source) return
                        val route = if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            "remote"
                        } else {
                            "local"
                        }
                        DiagnosticsLog.event(
                            "PlaybackService route=$route " +
                                "routingController=${deviceInfo.routingControllerId ?: "none"}",
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.throwable("PlaybackService player error code=${error.errorCodeName}", error)
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.event(
                            "PlaybackService media transition reason=$reason " +
                                "mediaId=${mediaItem?.mediaId?.take(120) ?: "none"}",
                        )
                        if (!::session.isInitialized) return
                        val route = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_WATCH_ROUTE)
                        session.setSessionActivity(sessionActivity(route))
                    }
                }

    private fun episodeAwarePlayer(delegate: Player): ForwardingPlayer =
        // The playlist holds one media item at a time (episodes resolve lazily, per provider),
        // but the session still advertises next/previous by forwarding them to the app's
        // episode navigator. That lights up Media3's built-in prev/next buttons and makes the
        // media notification and hardware/Bluetooth media keys switch episodes.
        object : ForwardingPlayer(delegate) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                    super.isCommandAvailable(command)

            override fun hasNextMediaItem(): Boolean = episodeNavigator != null
            override fun hasPreviousMediaItem(): Boolean = episodeNavigator != null

            override fun seekToNext() {
                DiagnosticsLog.event("PlaybackService seekToNext navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(1) ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                DiagnosticsLog.event("PlaybackService seekToNextMediaItem navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(1) ?: super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                DiagnosticsLog.event("PlaybackService seekToPrevious navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(-1) ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                DiagnosticsLog.event("PlaybackService seekToPreviousMediaItem navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(-1) ?: super.seekToPreviousMediaItem()
            }
        }

    private fun switchPlaybackPlayer(from: Player, to: Player, sessionPlayer: Player) {
        if (activePlayer === to) return
        val mediaItems = List(from.mediaItemCount) { from.getMediaItemAt(it) }
        val currentIndex = from.currentMediaItemIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        val currentPosition = from.currentPosition.coerceAtLeast(0L)
        val shouldPlay = from.playWhenReady

        activePlayer = to
        session.player = sessionPlayer
        if (mediaItems.isNotEmpty()) {
            to.setMediaItems(mediaItems, currentIndex, currentPosition)
            to.prepare()
            to.playWhenReady = shouldPlay
        }
        from.stop()
        from.clearMediaItems()
        DiagnosticsLog.event(
            "PlaybackService switched route=${if (to === castPlayer) "remote" else "local"} " +
                "items=${mediaItems.size} positionMs=$currentPosition play=$shouldPlay",
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = activePlayer
        DiagnosticsLog.event("PlaybackService.onTaskRemoved playing=${active?.playWhenReady == true}")
        if (active?.playWhenReady != true || active.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        DiagnosticsLog.event("PlaybackService.onDestroy")
        activeHttpFactory = null
        activePlayer = null
        episodeNavigator = null
        PlaybackStatus.update(false)
        if (::session.isInitialized) session.release()
        if (::castPlayer.isInitialized) castPlayer.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun sessionActivity(route: String?): PendingIntent {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(Routes.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            this,
            route?.hashCode() ?: 0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_WATCH_ROUTE = "watch_route"
        private const val FALLBACK_PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var activeHttpFactory: HttpDataSource.Factory? = null
        @Volatile
        private var activeUserAgent: String = FALLBACK_PLAYER_USER_AGENT
        @Volatile
        private var activePlaylistKey: String? = null
        @Volatile
        private var activePlayer: Player? = null

        /**
         * Set by the watch screen while it is visible: receives +1/-1 and resolves the
         * next/previous episode through the normal source-resolution flow. Invoked on the
         * main thread (session commands arrive on the application looper).
         */
        @Volatile
        var episodeNavigator: ((direction: Int) -> Unit)? = null

        /** Used when switching explicitly from native playback to a provider WebView. */
        fun stopActivePlayback() {
            DiagnosticsLog.event("PlaybackService.stopActivePlayback")
            activePlayer?.run {
                stop()
                clearMediaItems()
            }
        }

        @Volatile
        private var suppressMediaButtonResume = false

        /** Pause playback when the app is backgrounded while keeping the current media loaded. */
        fun pauseActivePlayback() {
            DiagnosticsLog.event("PlaybackService.pauseActivePlayback")
            val active = activePlayer ?: return
            if (active.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                DiagnosticsLog.event("PlaybackService.pauseActivePlayback skipped remote route")
                return
            }
            active.pause()
            // The media stays loaded so the notification can resume it, but stray media-button
            // events (Bluetooth reconnects) must not restart it while the app is away.
            suppressMediaButtonResume = true
        }

        /** The app is visible again (or playback deliberately restarted): buttons act normally. */
        fun allowMediaButtonResume() {
            suppressMediaButtonResume = false
        }

        /** Applies per-provider headers before Media3 creates manifest and segment data sources. */
        fun configureRequestHeaders(referer: String?, playlistKey: String?) {
            activePlaylistKey = playlistKey
            val safeReferer = referer ?: "https://www.miruro.to/"
            val refererUri = android.net.Uri.parse(safeReferer)
            val origin = refererUri.let { uri ->
                if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else safeReferer
            }
            val headers = mutableMapOf("Referer" to safeReferer, "Origin" to origin)
            if (refererUri.host.orEmpty().endsWith("flixcloud.cc", ignoreCase = true)) {
                val chromiumMajor = Regex("(?:Chrome|Chromium)/(\\d+)")
                    .find(activeUserAgent)?.groupValues?.get(1) ?: "137"
                headers += mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-CH-UA" to "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"$chromiumMajor\", \"Android WebView\";v=\"$chromiumMajor\"",
                    "Sec-CH-UA-Mobile" to "?1",
                    "Sec-CH-UA-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-site",
                    "X-Requested-With" to "com.miruronative",
                )
            }
            activeHttpFactory?.setDefaultRequestProperties(
                headers,
            )
            DiagnosticsLog.event(
                "PlaybackService.configureRequestHeaders " +
                    "refererHost=${android.net.Uri.parse(safeReferer).host ?: "unknown"} " +
                    "playlistKey=${playlistKey != null}",
            )
        }
    }
}

private fun Int.stateName(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}
