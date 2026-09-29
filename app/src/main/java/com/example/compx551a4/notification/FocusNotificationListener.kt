package com.example.compx551a4.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

//lets try to detect all the notification from other apps
//NotificationListenerService is an Android-provided service specifically designed to receive notification events.
class FocusNotificationListener : NotificationListenerService() {
    //Android calls onNotificationPosted() function when a notification is posted.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        Log.d(
            "FocusNotification",
            "Notification received from: $packageName"
        )
    }
}




