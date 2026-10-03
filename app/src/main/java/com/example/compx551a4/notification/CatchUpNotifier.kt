package com.example.compx551a4.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.FlushReason

// [C] The one grouped catch-up summary posted when a focus period ends (DESIGN.md §3).

/** Posts the catch-up summary. Split from the controller so it can be faked in tests. */
fun interface CatchUpPresenter {
    fun present(notifications: List<DeferredNotification>, reason: FlushReason)
}

/**
 * One notification, never a burst: a single inbox-style summary on its own channel at default
 * importance, so it makes a sound but never a heads-up. Missing POST_NOTIFICATIONS is logged and
 * ignored — the notifications are already in the shade, this only draws attention to them.
 */
class CatchUpNotifier(private val context: Context) : CatchUpPresenter {

    private val manager = NotificationManagerCompat.from(context)

    override fun present(notifications: List<DeferredNotification>, reason: FlushReason) {
        if (notifications.isEmpty()) return
        ensureChannel()

        val count = notifications.size
        val apps = notifications.map { it.appLabel }.distinct()
        val title = if (count == 1) "1 notification while you were focused" else "$count notifications while you were focused"
        val subtitle = when (reason) {
            FlushReason.MAX_HOLD_EXCEEDED -> "Held for the maximum time"
            FlushReason.HEADSET_REMOVED -> "Headset removed"
            FlushReason.MANUAL -> "Released by you"
            FlushReason.SERVICE_STOPPED -> "Focus session ended"
            FlushReason.FOCUS_BROKE -> apps.joinToString(", ")
        }

        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        notifications.takeLast(MAX_LINES).asReversed().forEach { n ->
            inbox.addLine(if (n.title.isBlank()) n.appLabel else "${n.appLabel}: ${n.title}")
        }
        if (count > MAX_LINES) inbox.setSummaryText("+${count - MAX_LINES} more")

        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(inbox)
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — catch-up summary skipped", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Catch-up summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "One summary of what arrived while you were focused"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CatchUpNotifier"
        const val CHANNEL_ID = "catch_up"
        const val NOTIFICATION_ID = 2001
        private const val MAX_LINES = 7
    }
}
