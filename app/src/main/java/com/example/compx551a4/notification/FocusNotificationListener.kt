package com.example.compx551a4.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.compx551a4.FocusApp
import com.example.compx551a4.core.DeferredNotification

// [C] NotificationListenerService in log-only mode: records what arrived during focus.

/**
 * Runs whenever the user has granted notification access, whether or not a session is on; the
 * controller decides what to keep. Our own notifications, ongoing ones (media, navigation, the
 * foreground service itself) and group summaries are not things the user is waiting on, so they
 * never enter the queue.
 */
class FocusNotificationListener : NotificationListenerService() {

    private val controller: NotificationController?
        get() = (application as? FocusApp)?.container?.suppressor as? NotificationController

    override fun onListenerConnected() {
        Log.d(TAG, "listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isUserFacing(sbn)) return
        val controller = controller ?: return
        if (!controller.isFocused) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        val notification = DeferredNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title,
            postedAtMs = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            category = sbn.notification.category,
        )
        Log.d(TAG, "deferring ${sbn.packageName}: ${notification.title}")
        controller.handleNotification(notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        controller?.handleRemoved(sbn.key)
    }

    private fun isUserFacing(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        return true
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val TAG = "FocusNotificationListener"
    }
}
