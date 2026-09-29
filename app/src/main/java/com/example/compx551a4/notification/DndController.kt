package com.example.compx551a4.notification

import android.app.NotificationManager
import android.content.Context

class DndController(
    private val context: Context//Context gives our class access to Android system services.
) {
   //NotificationManager is the Android system component that lets us work with notification-related settings, including the DND/Zen system.
    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)
}