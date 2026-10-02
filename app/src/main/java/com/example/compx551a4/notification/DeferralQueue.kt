package com.example.compx551a4.notification
import com.example.compx551a4.core.DeferredNotification

// [C] Holds what arrived during focus, max-hold timer, grouped catch-up summary on break.
/**
 * this is considered as memory that collects the blocked notification in a queue and remembered it
 * this is the queue that will hold all the notification(queue Manager)
 */
class DeferralQueue {

    private val notifications = mutableListOf<DeferredNotification>()

    /**
     * this function adds notification in list
     */
    fun add(notification: DeferredNotification) {
        notifications.add(notification)
    }

    /**
     * this function returns all the notification
     */
    fun getNotifications(): List<DeferredNotification> {
        return notifications.toList()
    }

    /**
     * this function clears the queue when all notification has been returned
     */
    fun clear() {
        notifications.clear()
    }

}