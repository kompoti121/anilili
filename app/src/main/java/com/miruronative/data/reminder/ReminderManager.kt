package com.miruronative.data.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.miruronative.MainActivity
import com.miruronative.data.model.AiringSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ReminderManager {
    private const val PREFS = "anilili_reminders"
    private const val KEY_IDS = "scheduled"
    const val CHANNEL_ID = "airing_reminders"

    private lateinit var context: Context
    private val _scheduled = MutableStateFlow<Set<String>>(emptySet())
    val scheduled = _scheduled.asStateFlow()

    fun init(appContext: Context) {
        context = appContext.applicationContext
        _scheduled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet()).orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New episode reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts shortly before a saved anime episode airs"
            },
        )
    }

    fun id(item: AiringSchedule): String = "${item.media?.id}:${item.episode}:${item.airingAt}"
    fun isScheduled(item: AiringSchedule): Boolean = id(item) in _scheduled.value

    fun toggle(item: AiringSchedule) {
        if (isScheduled(item)) cancel(item) else schedule(item)
    }

    private fun schedule(item: AiringSchedule) {
        val media = item.media ?: return
        val intent = reminderIntent(item).putExtra("title", media.title.preferred)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(item),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = (item.airingAt * 1000L - 10 * 60 * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        update(_scheduled.value + id(item))
    }

    private fun cancel(item: AiringSchedule) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(item),
            reminderIntent(item),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending?.cancel()
        update(_scheduled.value - id(item))
    }

    private fun reminderIntent(item: AiringSchedule) =
        Intent(context, AiringReminderReceiver::class.java)
            .putExtra("episode", item.episode)
            .putExtra("mediaId", item.media?.id ?: 0)
            .putExtra("reminderId", id(item))

    private fun requestCode(item: AiringSchedule): Int = 31 * (item.media?.id ?: 0) + item.episode

    private fun update(value: Set<String>) {
        _scheduled.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_IDS, value).apply()
    }

    fun markDelivered(reminderId: String?) {
        if (reminderId != null) update(_scheduled.value - reminderId)
    }
}

class AiringReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderManager.markDelivered(intent.getStringExtra("reminderId"))
        val title = intent.getStringExtra("title") ?: "A saved anime"
        val episode = intent.getIntExtra("episode", 0)
        val mediaId = intent.getIntExtra("mediaId", 0)
        val open = PendingIntent.getActivity(
            context,
            mediaId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("New episode airing soon")
            .setContentText("$title • Episode $episode")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(31 * mediaId + episode, notification)
    }
}
