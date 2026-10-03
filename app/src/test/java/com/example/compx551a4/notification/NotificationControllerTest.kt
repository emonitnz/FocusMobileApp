package com.example.compx551a4.notification

import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.PolicySettings
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.SessionStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationControllerTest {

    private class FakeGate : FocusModeGate {
        val calls = mutableListOf<Boolean>()
        var policies = 0
        val enabled: Boolean get() = calls.lastOrNull() ?: false
        override fun setFocusMode(enabled: Boolean) { calls += enabled }
        override fun updatePolicy(settings: PolicySettings) { policies++ }
    }

    private class FakePresenter : CatchUpPresenter {
        val shown = mutableListOf<Pair<List<DeferredNotification>, FlushReason>>()
        override fun present(notifications: List<DeferredNotification>, reason: FlushReason) {
            shown += notifications to reason
        }
    }

    private class FakeRecorder : SessionRecorder {
        val deferred = mutableListOf<DeferredNotification>()
        val flushes = mutableListOf<Triple<Int, FlushReason, Long>>()
        override val stats: Flow<SessionStats> = emptyFlow()
        override suspend fun onSessionStart(atMs: Long) = Unit
        override suspend fun onDecision(decision: FocusDecision) = Unit
        override suspend fun onDeferred(notification: DeferredNotification) { deferred += notification }
        override suspend fun onFlush(count: Int, reason: FlushReason, atMs: Long) { flushes += Triple(count, reason, atMs) }
        override suspend fun onSessionEnd(atMs: Long) = Unit
    }

    private class FakePolicy(initial: PolicySettings = PolicySettings()) : NotificationPolicy {
        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<PolicySettings> = _settings
        override suspend fun update(settings: PolicySettings) { _settings.value = settings }
    }

    private class Harness(scope: CoroutineScope, settings: PolicySettings = PolicySettings()) {
        val gate = FakeGate()
        val presenter = FakePresenter()
        val recorder = FakeRecorder()
        val policy = FakePolicy(settings)
        var now = 1_000_000L
        val controller = NotificationController(
            gate = gate,
            queue = DeferralQueue(),
            presenter = presenter,
            recorder = recorder,
            policy = policy,
            scope = scope,
            clock = { now },
        )
    }

    private fun note(n: Int, pkg: String = "com.example.chat", category: String? = null) = DeferredNotification(
        key = "key-$n",
        packageName = pkg,
        appLabel = "Chat",
        title = "message $n",
        postedAtMs = 0L,
        category = category,
    )

    @Test
    fun `focused turns the rule on and anything else turns it off`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.controller.apply(FocusState.FOCUSED)
        assertTrue(h.gate.enabled)
        h.controller.apply(FocusState.UNKNOWN)
        assertFalse(h.gate.enabled)
        h.controller.apply(FocusState.NOT_FOCUSED)
        assertEquals(listOf(true, false, false), h.gate.calls)
    }

    @Test
    fun `notifications are only held while focused`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.controller.handleNotification(note(1))
        assertTrue(h.controller.deferred.value.isEmpty())

        h.controller.apply(FocusState.FOCUSED)
        h.controller.handleNotification(note(2))
        h.controller.handleNotification(note(3))
        assertEquals(listOf("key-2", "key-3"), h.controller.deferred.value.map { it.key })
        assertEquals(2, h.recorder.deferred.size)
    }

    @Test
    fun `focus break releases the queue as one summary and records the flush`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.controller.apply(FocusState.FOCUSED)
        h.controller.handleNotification(note(1))
        h.controller.handleNotification(note(2))

        h.controller.apply(FocusState.NOT_FOCUSED)

        assertTrue(h.controller.deferred.value.isEmpty())
        assertEquals(1, h.presenter.shown.size)
        assertEquals(2, h.presenter.shown.single().first.size)
        assertEquals(FlushReason.FOCUS_BROKE, h.presenter.shown.single().second)
        assertEquals(Triple(2, FlushReason.FOCUS_BROKE, h.now), h.recorder.flushes.single())
        // The rule went off before the summary was posted, so the summary itself is not silenced.
        assertFalse(h.gate.enabled)
    }

    @Test
    fun `an empty queue flushes silently`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.controller.apply(FocusState.FOCUSED)
        h.controller.apply(FocusState.NOT_FOCUSED)
        h.controller.flush(FlushReason.MANUAL)
        assertTrue(h.presenter.shown.isEmpty())
        assertTrue(h.recorder.flushes.isEmpty())
    }

    @Test
    fun `an updated notification replaces its earlier entry`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.controller.apply(FocusState.FOCUSED)
        h.controller.handleNotification(note(1))
        h.controller.handleNotification(note(1).copy(title = "edited"))
        assertEquals(listOf("edited"), h.controller.deferred.value.map { it.title })
    }

    @Test
    fun `a dismissed notification leaves the queue`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.controller.apply(FocusState.FOCUSED)
        h.controller.handleNotification(note(1))
        h.controller.handleNotification(note(2))
        h.controller.handleRemoved("key-1")
        assertEquals(listOf("key-2"), h.controller.deferred.value.map { it.key })
    }

    @Test
    fun `max hold releases the queue while focus continues`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, PolicySettings(maxHoldMinutes = 5))
        h.controller.apply(FocusState.FOCUSED)
        h.controller.handleNotification(note(1))

        advanceTimeBy(4 * 60_000L)
        assertEquals(1, h.controller.deferred.value.size)

        advanceTimeBy(60_000L + 1)
        assertTrue(h.controller.deferred.value.isEmpty())
        assertEquals(FlushReason.MAX_HOLD_EXCEEDED, h.presenter.shown.single().second)
        assertTrue("focus mode stays on", h.gate.enabled)

        // The timer restarts with the next arrival rather than firing immediately.
        h.controller.handleNotification(note(2))
        advanceTimeBy(60_000L)
        assertEquals(1, h.controller.deferred.value.size)
    }

    @Test
    fun `breakthrough notifications are flagged but still listed`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, PolicySettings(allowCalls = true, allowedPackages = setOf("com.example.pager")))
        h.controller.apply(FocusState.FOCUSED)
        h.controller.handleNotification(note(1, category = "call"))
        h.controller.handleNotification(note(2, pkg = "com.example.pager"))
        h.controller.handleNotification(note(3))
        assertEquals(listOf(true, true, false), h.controller.deferred.value.map { it.breakthrough })
    }

    @Test
    fun `policy changes reach the rule`() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val before = h.gate.policies
        h.policy.update(PolicySettings(allowAlarms = false))
        assertEquals(before + 1, h.gate.policies)
    }
}
