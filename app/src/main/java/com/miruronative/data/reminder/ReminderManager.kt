package com.miruronative.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.data.model.AiringSchedule
import com.miruronative.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class ScheduledReminder(
    val id: String,
    val mediaId: Int,
    val episode: Int,
    val title: String,
    val airingAt: Long,
)

@SuppressLint("StaticFieldLeak") // Always assigned applicationContext; never an Activity or receiver context.
object ReminderManager {
    private const val PREFS = "anilili_reminders"
    private const val KEY_RECORDS = "scheduled_records"
    const val CHANNEL_ID = "airing_reminders"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var context: Context
    private var records: List<ScheduledReminder> = emptyList()
    private val _scheduled = MutableStateFlow<Set<String>>(emptySet())
    val scheduled = _scheduled.asStateFlow()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        createChannel()
        val now = System.currentTimeMillis() / 1000L
        records = decodeRecords().filter { it.airingAt > now }
        records.forEach(::scheduleAlarm)
        persist(records)
    }

    fun id(item: AiringSchedule): String = "${item.media?.id}:${item.episode}:${item.airingAt}"
    fun isScheduled(item: AiringSchedule): Boolean = id(item) in _scheduled.value

    fun toggle(item: AiringSchedule) {
        if (isScheduled(item)) cancel(item) else schedule(item)
    }

    private fun schedule(item: AiringSchedule) {
        val media = item.media ?: return
        val record = ScheduledReminder(
            id = id(item),
            mediaId = media.id,
            episode = item.episode,
            title = media.title.preferred,
            airingAt = item.airingAt,
        )
        scheduleAlarm(record)
        update(listOf(record) + records.filterNot { it.id == record.id })
    }

    private fun cancel(item: AiringSchedule) {
        val record = records.firstOrNull { it.id == id(item) } ?: return
        alarmPendingIntent(record, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
        update(records.filterNot { it.id == record.id })
    }

    private fun scheduleAlarm(record: ScheduledReminder) {
        val pending = alarmPendingIntent(record, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val desired = record.airingAt * 1000L - 10 * 60 * 1000L
        val triggerAt = desired.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun alarmPendingIntent(record: ScheduledReminder, mode: Int): PendingIntent? {
        val intent = Intent(context, AiringReminderReceiver::class.java)
            .putExtra("title", record.title)
            .putExtra("episode", record.episode)
            .putExtra("mediaId", record.mediaId)
            .putExtra("reminderId", record.id)
        return PendingIntent.getBroadcast(
            context,
            requestCode(record.mediaId, record.episode),
            intent,
            mode or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New episode reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts shortly before a saved anime episode airs"
            },
        )
    }

    private fun update(value: List<ScheduledReminder>) {
        records = value
        persist(value)
    }

    private fun persist(value: List<ScheduledReminder>) {
        _scheduled.value = value.mapTo(linkedSetOf()) { it.id }
        val encoded = json.encodeToString(ListSerializer(ScheduledReminder.serializer()), value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, encoded).apply()
    }

    private fun decodeRecords(): List<ScheduledReminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ScheduledReminder.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun markDelivered(reminderId: String?) {
        if (reminderId != null) update(records.filterNot { it.id == reminderId })
    }

    private fun requestCode(mediaId: Int, episode: Int): Int = 31 * mediaId + episode
}

class AiringReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderManager.markDelivered(intent.getStringExtra("reminderId"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = intent.getStringExtra("title") ?: "A saved anime"
        val episode = intent.getIntExtra("episode", 0)
        val mediaId = intent.getIntExtra("mediaId", 0)
        val open = PendingIntent.getActivity(
            context,
            mediaId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.detail(mediaId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New episode airing soon")
            .setContentText("$title • Episode $episode")
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "Open details", open)
            .setAutoCancel(true)
            .setGroup(ReminderManager.CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(31 * mediaId + episode, notification)
    }
}

/** Recreates alarms cleared by reboot or app replacement. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ReminderManager.init(context)
        AutomaticReleaseManager.init(context)
        ReleaseSyncScheduler.runNow(context)
    }
}
