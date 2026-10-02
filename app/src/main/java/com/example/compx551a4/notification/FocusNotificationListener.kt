package com.example.compx551a4.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.compx551a4.core.DeferredNotification

class FocusNotificationListener : NotificationListenerService() {

    // Sends received notifications to the notification controller.
    private lateinit var notificationController: NotificationController

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        // Convert Android's notification into our shared notification model.
        val notification = DeferredNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = sbn.packageName,
            title = sbn.notification.extras
                .getString("android.title")
                ?: "",
            postedAtMs = System.currentTimeMillis(),
            category = sbn.notification.category
        )

        // Useful for checking that Android is sending notifications to us.
        Log.d(
            "FocusNotification",
            "Notification received from: ${sbn.packageName}"
        )

        // Pass the notification to deferral queue so it can decide whether to defer it.
        notificationController.handleNotification(notification)
    }
}