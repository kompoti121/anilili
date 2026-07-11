package com.miruronative.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.miruronative.MainActivity
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

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
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
                })
            }

        val activityIntent = Intent(this, MainActivity::class.java)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        activeHttpFactory = null
        PlaybackStatus.update(false)
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var activeHttpFactory: DefaultHttpDataSource.Factory? = null

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
