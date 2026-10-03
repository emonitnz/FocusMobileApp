package com.example.compx551a4.notification

import com.example.compx551a4.core.DeferredNotification

// [C] Holds what arrived during focus, max-hold timer, grouped catch-up summary on break.

/**
 * The notifications held back during the current focus period, oldest first.
 *
 * Keyed on the platform's notification key: an app that updates a notification replaces its
 * entry rather than appearing twice in the catch-up summary. Safe to call from the listener's
 * thread and the service's coroutine at once.
 */
class DeferralQueue {

    private val byKey = LinkedHashMap<String, DeferredNotification>()

    @Synchronized
    fun add(notification: DeferredNotification) {
        byKey.remove(notification.key)
        byKey[notification.key] = notification
    }

    /** The user dismissed or the app cancelled it, so the summary should no longer mention it. */
    @Synchronized
    fun remove(key: String): Boolean = byKey.remove(key) != null

    @Synchronized
    fun getNotifications(): List<DeferredNotification> = byKey.values.toList()

    @Synchronized
    fun clear() = byKey.clear()

    val size: Int
        @Synchronized get() = byKey.size

    val isEmpty: Boolean get() = size == 0
}
