package com.miruronative.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AuthManager
import com.miruronative.data.library.LibraryStore
import com.miruronative.data.model.Media
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.nav.Routes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class ReleaseAlarm(
    val id: String,
    val mediaId: Int,
    val episode: Int,
    val title: String,
    val airingAt: Long,
)

/** Schedules one local notification at the release time of each tracked anime's next episode. */
object AutomaticReleaseManager {
    const val CHANNEL_ID = "automatic_episode_releases"
    private const val PREFS = "anilili_release_alerts"
    private const val KEY_ALARMS = "alarms"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var appContext: Context
    private var alarms: List<ReleaseAlarm> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New episode releases", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a new episode airs for anime saved here or on AniList"
            },
        )
        val oldestUseful = System.currentTimeMillis() / 1000L - 24 * 60 * 60
        alarms = read().filter { it.airingAt >= oldestUseful }
        alarms.forEach(::schedule)
        persist(alarms)
    }

    fun sync(media: Collection<Media>) {
        if (!SettingsStore.releaseNotifications.value) {
            cancelAll()
            return
        }
        val now = System.currentTimeMillis() / 1000L
        val desired = media.mapNotNull { item ->
            val next = item.nextAiringEpisode ?: return@mapNotNull null
            val episode = next.episode ?: return@mapNotNull null
            val airingAt = next.airingAt ?: return@mapNotNull null
            if (airingAt <= now) return@mapNotNull null
            ReleaseAlarm(
                id = "${item.id}:$episode:$airingAt",
                mediaId = item.id,
                episode = episode,
                title = item.title.preferred,
                airingAt = airingAt,
            )
        }.distinctBy { it.id }

        val desiredIds = desired.mapTo(hashSetOf()) { it.id }
        alarms.filterNot { it.id in desiredIds }.forEach(::cancel)
        desired.forEach(::schedule)
        alarms = desired
        persist(alarms)
    }

    fun cancelAll() {
        if (!::appContext.isInitialized) return
        alarms.forEach(::cancel)
        alarms = emptyList()
        persist(alarms)
    }

    fun markDelivered(id: String?) {
        if (id == null || !::appContext.isInitialized) return
        alarms = alarms.filterNot { it.id == id }
        persist(alarms)
    }

    private fun schedule(alarm: ReleaseAlarm) {
        val triggerAt = (alarm.airingAt * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        appContext.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return,
        )
    }

    private fun cancel(alarm: ReleaseAlarm) {
        pendingIntent(alarm, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            appContext.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    private fun pendingIntent(alarm: ReleaseAlarm, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        alarm.id.hashCode(),
        Intent(appContext, AutomaticReleaseReceiver::class.java)
            .putExtra("releaseId", alarm.id)
            .putExtra("mediaId", alarm.mediaId)
            .putExtra("episode", alarm.episode)
            .putExtra("title", alarm.title),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun persist(value: List<ReleaseAlarm>) {
        val raw = json.encodeToString(ListSerializer(ReleaseAlarm.serializer()), value)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ALARMS, raw).apply()
    }

    private fun read(): List<ReleaseAlarm> {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALARMS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReleaseAlarm.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}

class AutomaticReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomaticReleaseManager.markDelivered(intent.getStringExtra("releaseId"))
        ReleaseSyncScheduler.runNow(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val mediaId = intent.getIntExtra("mediaId", 0)
        val episode = intent.getIntExtra("episode", 0)
        val title = intent.getStringExtra("title") ?: "A saved anime"
        val open = PendingIntent.getActivity(
            context,
            mediaId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.detail(mediaId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AutomaticReleaseManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New episode released")
            .setContentText("$title • Episode $episode")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Episode $episode of $title has just aired."))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "View anime", open)
            .setAutoCancel(true)
            .setGroup(AutomaticReleaseManager.CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(17 * mediaId + episode, notification)
    }
}

object ReleaseSyncScheduler {
    private const val PERIODIC_NAME = "automatic-release-sync"
    private const val IMMEDIATE_NAME = "automatic-release-sync-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReleaseSyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        runNow(context)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReleaseSyncWorker>()
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class ReleaseSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SettingsStore.releaseNotifications.value) {
            AutomaticReleaseManager.cancelAll()
            return Result.success()
        }
        return try {
            val repo = AppGraph.repository
            val tracked = mutableListOf<Media>()
            tracked += coroutineScope {
                LibraryStore.watchlist.value.map { saved ->
                    async { runCatching { repo.animeInfo(saved.anilistId) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
            if (AuthManager.isLoggedIn) {
                val viewer = runCatching { repo.viewer() }.getOrNull()
                if (viewer != null) {
                    val activeStatuses = setOf("CURRENT", "REPEATING", "PLANNING", "PAUSED")
                    tracked += runCatching { repo.userAnimeList(viewer.id) }.getOrNull()
                        ?.lists.orEmpty()
                        .filter { it.status in activeStatuses }
                        .flatMap { it.entries }
                        .mapNotNull { it.media }
                    tracked += runCatching { repo.favouriteAnime() }.getOrDefault(emptyList())
                }
            }
            AutomaticReleaseManager.sync(tracked.distinctBy { it.id })
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
