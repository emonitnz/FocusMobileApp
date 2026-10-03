package com.example.compx551a4.notification

import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.PolicySettings
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.Suppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// [C] The Suppressor: focus state in, DND rule + deferral queue + catch-up summary out.

/**
 * Workload C's [Suppressor].
 *
 * - FOCUSED turns the DND rule on. Everything the listener then sees is queued.
 * - NOT_FOCUSED and UNKNOWN turn it off and release the queue as one summary (fail open —
 *   DESIGN.md §3: anything short of confident focus delivers normally).
 * - The max-hold valve releases the queue [PolicySettings.maxHoldMinutes] after the first
 *   notification was held, even if focus continues, and the timer restarts with the next arrival.
 *
 * The breakthrough allowlist is enforced by the platform through the rule's policy; notifications
 * that match it are still recorded, flagged, so the summary and the stats tell them apart.
 */
class NotificationController(
    private val gate: FocusModeGate,
    private val queue: DeferralQueue,
    private val presenter: CatchUpPresenter,
    private val recorder: SessionRecorder,
    private val policy: NotificationPolicy,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : Suppressor {

    private val _deferred = MutableStateFlow<List<DeferredNotification>>(emptyList())
    override val deferred: StateFlow<List<DeferredNotification>> = _deferred.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var currentState = FocusState.UNKNOWN

    private var maxHoldJob: Job? = null

    init {
        scope.launch { policy.settings.collect { gate.updatePolicy(it) } }
    }

    val isFocused: Boolean get() = currentState == FocusState.FOCUSED

    override suspend fun apply(state: FocusState) {
        currentState = state
        when (state) {
            FocusState.FOCUSED -> gate.setFocusMode(true)
            FocusState.NOT_FOCUSED, FocusState.UNKNOWN -> {
                gate.setFocusMode(false)
                if (!queue.isEmpty) flush(FlushReason.FOCUS_BROKE)
            }
        }
    }

    /** Called by [FocusNotificationListener] for every notification it sees. */
    fun handleNotification(notification: DeferredNotification) {
        if (!isFocused) return
        val settings = policy.settings.value
        val entry = notification.copy(breakthrough = BreakthroughPolicy.isBreakthrough(notification, settings))
        scope.launch {
            mutex.withLock {
                val wasEmpty = queue.isEmpty
                queue.add(entry)
                publish()
                if (wasEmpty) startMaxHold(settings.maxHoldMinutes)
            }
            recorder.onDeferred(entry)
        }
    }

    /** The user dismissed it from the shade, so it is no longer waiting for them. */
    fun handleRemoved(key: String) {
        scope.launch {
            mutex.withLock {
                if (queue.remove(key)) {
                    publish()
                    if (queue.isEmpty) cancelMaxHold()
                }
            }
        }
    }

    override suspend fun flush(reason: FlushReason) {
        val released = mutex.withLock {
            cancelMaxHold()
            val items = queue.getNotifications()
            queue.clear()
            publish()
            items
        }
        if (released.isEmpty()) return
        presenter.present(released, reason)
        recorder.onFlush(released.size, reason, clock())
    }

    private fun publish() {
        _deferred.value = queue.getNotifications()
    }

    private fun startMaxHold(minutes: Int) {
        cancelMaxHold()
        if (minutes <= 0) return
        maxHoldJob = scope.launch {
            delay(minutes * 60_000L)
            flush(FlushReason.MAX_HOLD_EXCEEDED)
        }
    }

    private fun cancelMaxHold() {
        maxHoldJob?.cancel()
        maxHoldJob = null
    }
}
