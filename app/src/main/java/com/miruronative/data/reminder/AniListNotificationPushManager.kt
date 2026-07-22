package com.miruronative.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miruronative.MainActivity
import com.miruronative.R
import com.miruronative.data.model.AppNotification
import com.miruronative.ui.nav.Routes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bridges AniList's in-app notification feed to Android notifications. AniList exposes unread
 * state as a count instead of per-item flags, so delivered IDs are stored locally to avoid
 * re-pushing the same unread items during every periodic sync.
 */
object AniListNotificationPushManager {
    const val CHANNEL_ID = "anilist_account_notifications"
    private const val PREFS = "anilili_anilist_notifications"
    private const val KEY_DELIVERED_IDS = "delivered_ids"
    private const val MAX_DELIVERED_IDS = 400
    private const val MAX_PUSHES_PER_SYNC = 8

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        createChannel(appContext)
    }

    fun notifyUnread(context: Context, items: List<AppNotification>) {
        val app = if (::appContext.isInitialized) appContext else context.applicationContext.also {
            appContext = it
            createChannel(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val delivered = readDelivered(app)
        val deliveredSet = delivered.toHashSet()
        val freshUnread = items.filter { it.unread && it.id !in deliveredSet }
        if (freshUnread.isEmpty()) return

        freshUnread
            .take(MAX_PUSHES_PER_SYNC)
            .asReversed()
            .forEach { item -> notify(app, item) }
        postGroupSummary(app)

        writeDelivered(
            app,
            (freshUnread.map { it.id } + delivered)
                .distinct()
                .take(MAX_DELIVERED_IDS),
        )
    }

    fun clearDelivered(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DELIVERED_IDS)
            .apply()
    }

    /**
     * Removes this channel's notifications from the system tray. Called when the in-app
     * Notifications page opens (the user is looking at the same items) and on "mark all read",
     * so the shade never shows entries the app already considers handled.
     */
    fun dismissAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = notificationManager(context)
        runCatching {
            val active = manager.activeNotifications
            val matching = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                active.filter { it.notification.channelId == CHANNEL_ID }
            } else {
                active.toList()
            }
            matching
                .forEach { manager.cancel(it.id) }
        }
    }

    private fun notify(context: Context, item: AppNotification) {
        val route = item.mediaId?.let(Routes::detail) ?: Routes.NOTIFICATIONS
        val open = PendingIntent.getActivity(
            context,
            item.id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, route)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (item.kind) {
            AppNotification.Kind.AIRING -> "New episode on AniList"
            AppNotification.Kind.MEDIA -> "AniList media update"
            AppNotification.Kind.SOCIAL -> "AniList notification"
        }
        val text = itemText(item)
        val actionLabel = if (item.mediaId != null) "View anime" else "Open notifications"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, actionLabel, open)
            .setAutoCancel(true)
            .setGroup(CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager(context).notify(item.id, notification)
    }

    /** Without a summary, Android never collapses the group — each push stays a separate row. */
    private fun postGroupSummary(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.NOTIFICATIONS)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AniList notifications")
            .setContentIntent(open)
            .setGroup(CHANNEL_ID)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        notificationManager(context).notify(SUMMARY_ID, summary)
    }

    private const val SUMMARY_ID = -1001

    private fun itemText(item: AppNotification): String = when {
        item.badge != null -> "${item.title} - ${item.badge}"
        item.detail != null -> "${item.title} - ${item.detail}"
        else -> item.title
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager(context).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AniList notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Pushes unread notifications from your AniList account"
                },
            )
        }
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun readDelivered(context: Context): List<Int> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DELIVERED_IDS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Int.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeDelivered(context: Context, ids: List<Int>) {
        val raw = json.encodeToString(ListSerializer(Int.serializer()), ids)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DELIVERED_IDS, raw)
            .apply()
    }
}
