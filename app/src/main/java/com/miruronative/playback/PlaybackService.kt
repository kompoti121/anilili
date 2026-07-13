package com.miruronative.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
 * Owns the single ExoPlayer instance so playback survives activity backgrounding and exposes
 * Android notification, lock-screen, headset, and Bluetooth controls through a MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var httpFactory: DefaultHttpDataSource.Factory

    override fun onCreate() {
        super.onCreate()
        httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(PLAYER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        activeHttpFactory = httpFactory
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSource))
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
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        PlaybackStatus.update(isPlaying)
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        if (!::session.isInitialized) return
                        val route = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_WATCH_ROUTE)
                        session.setSessionActivity(sessionActivity(route))
                    }
                })
            }
        activePlayer = player

        // The playlist holds one media item at a time (episodes resolve lazily, per provider),
        // but the session still advertises next/previous by forwarding them to the app's
        // episode navigator. That lights up Media3's built-in prev/next buttons and makes the
        // media notification and hardware/Bluetooth media keys switch episodes.
        val episodeAwarePlayer = object : ForwardingPlayer(player) {
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
                episodeNavigator?.invoke(1) ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                episodeNavigator?.invoke(1) ?: super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                episodeNavigator?.invoke(-1) ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                episodeNavigator?.invoke(-1) ?: super.seekToPreviousMediaItem()
            }
        }

        session = MediaSession.Builder(this, episodeAwarePlayer)
            .setSessionActivity(sessionActivity(null))
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!::player.isInitialized || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        activeHttpFactory = null
        activePlayer = null
        episodeNavigator = null
        PlaybackStatus.update(false)
        if (::session.isInitialized) session.release()
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
        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var activeHttpFactory: DefaultHttpDataSource.Factory? = null
        @Volatile
        private var activePlayer: ExoPlayer? = null

        /**
         * Set by the watch screen while it is visible: receives +1/-1 and resolves the
         * next/previous episode through the normal source-resolution flow. Invoked on the
         * main thread (session commands arrive on the application looper).
         */
        @Volatile
        var episodeNavigator: ((direction: Int) -> Unit)? = null

        /** Used when switching explicitly from native playback to a provider WebView. */
        fun stopActivePlayback() {
            activePlayer?.run {
                stop()
                clearMediaItems()
            }
        }

        /** Pause playback when the app is backgrounded while keeping the current media loaded. */
        fun pauseActivePlayback() {
            activePlayer?.pause()
        }

        /** Applies per-provider headers before Media3 creates manifest and segment data sources. */
        fun configureRequestHeaders(referer: String?) {
            val safeReferer = referer ?: "https://www.miruro.to/"
            val origin = android.net.Uri.parse(safeReferer).let { uri ->
                if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else safeReferer
            }
            activeHttpFactory?.setDefaultRequestProperties(
                mapOf("Referer" to safeReferer, "Origin" to origin),
            )
        }
    }
}
