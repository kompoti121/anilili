package com.miruronative.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.ui.nav.Routes

/** Keeps episode downloads alive after the app is backgrounded or its activity is destroyed. */
@OptIn(UnstableApi::class)
class EpisodeDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    NOTIFICATION_CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_description,
) {
    private val notificationHelper by lazy {
        DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager =
        EpisodeDownloads.getDownloadManager(this)

    override fun getScheduler(): Scheduler =
        PlatformScheduler(this, DOWNLOAD_JOB_ID)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = notificationHelper.buildProgressNotification(
        this,
        R.drawable.ic_notification,
        libraryPendingIntent(),
        null,
        downloads,
        notMetRequirements,
    )

    private fun libraryPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Routes.EXTRA_ROUTE, Routes.MORE)
        }
        return PendingIntent.getActivity(
            this,
            DOWNLOAD_JOB_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 7_201
        const val DOWNLOAD_JOB_ID = 7_202
        const val NOTIFICATION_CHANNEL_ID = "episode_downloads"
    }
}
