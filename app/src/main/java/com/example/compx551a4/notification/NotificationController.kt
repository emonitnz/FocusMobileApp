package com.example.compx551a4.notification

import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.Suppressor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls notification suppression based on the focus state.
 *
 * When the user is focused, DND is enabled and incoming notifications
 * can be stored in the deferral queue.
 *
 * When the user is not focused, DND is disabled.
 */
class NotificationController(
    private val dndController: DndController,
    private val deferralQueue: DeferralQueue
) : Suppressor {

    // Keeps track of the notifications currently waiting in the queue.
    private val _deferred = MutableStateFlow<List<DeferredNotification>>(emptyList())

    // Allows other parts of the app to observe the deferred notifications.
    override val deferred: StateFlow<List<DeferredNotification>> =
        _deferred.asStateFlow()

    // Keeps track of the latest focus state received from FocusEngine.
    private var currentState = FocusState.UNKNOWN

    init {
        // Register the Focus Mode DND rule with Android.
        dndController.createRule()
    }

    /**
     * Applies the latest focus state to the notification system.
     *
     * FOCUSED -> DND ON
     * NOT_FOCUSED -> DND OFF
     * UNKNOWN -> DND OFF (fail open)
     */
    override suspend fun apply(state: FocusState) {

        // Remember the latest state so incoming notifications
        // know whether they should be deferred.
        currentState = state

        when (state) {

            FocusState.FOCUSED -> {
                // User is focused, so block notification interruptions.
                dndController.setFocusMode(true)
            }

            FocusState.NOT_FOCUSED,
            FocusState.UNKNOWN -> {
                // User is not safely known to be focused,
                // so allow notifications normally.
                dndController.setFocusMode(false)
            }
        }

        // Update the observable list after applying the state.
        updateDeferredNotifications()
    }

    /**
     * Receives a notification from FocusNotificationListener.
     *
     * Notifications are only added to the queue while the user
     * is currently focused.
     */
    fun handleNotification(notification: DeferredNotification) {

        if (currentState == FocusState.FOCUSED) {

            // Store the notification in our queue.
            deferralQueue.add(notification)

            // Make the updated queue visible to the rest of the app.
            updateDeferredNotifications()
        }
    }

    /**
     * Releases all deferred notifications when focus ends
     * or another safety condition requires the queue to be flushed.
     */
    override suspend fun flush(reason: FlushReason) {

        // Get the notifications that were being held.
        val notifications = deferralQueue.getNotifications()

        // TODO: Create the grouped catch-up summary here.
        // For now, we simply clear the queue.

        deferralQueue.clear()

        // Tell observers that there are no deferred notifications left.
        _deferred.value = emptyList()
    }

    /**
     * Copies the current queue into the StateFlow so other parts
     * of the app can observe the latest deferred notifications.
     */
    private fun updateDeferredNotifications() {
        _deferred.value = deferralQueue.getNotifications()
    }
}