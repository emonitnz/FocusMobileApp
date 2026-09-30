package com.example.compx551a4.notification
//can be considered as memory that collected the blocked notification and remembered it
// [C] Holds what arrived during focus, max-hold timer, grouped catch-up summary on break.

data class DeferredNotification(
    val packageName: String,//which app the the notification is from
    val title: String,
    val text: String
)

/**
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