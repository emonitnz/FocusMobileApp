package com.example.compx551a4.notification
//decision maker to check which notification to hold and which one to allow when DNDController will halt all notification
import android.service.notification.StatusBarNotification

class BreakthroughPolicy {
    //it means give me a notification and it will tell if its true or false(boolean)
    fun shouldAllow(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName

        return packageName == "com.android.dialer"
    }
}