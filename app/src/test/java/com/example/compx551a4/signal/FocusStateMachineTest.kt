package com.example.compx551a4.signal

import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusStateMachineTest {

    private val thresholds = FocusThresholds.PLACEHOLDER.copy(
        tHighZ = 0.5,
        tLowZ = -0.2,
        entryDwellSec = 5,
        exitDwellSec = 10,
    )

    private val high = 1.0
    private val low = -1.0
    private val between = 0.1

    /** Feeds [values] at 1 Hz starting at [startSec] and returns the state after each one. */
    private fun FocusStateMachine.feed(
        values: List<Double>,
        startSec: Int = 0,
        t: FocusThresholds = thresholds,
    ): List<FocusState> = values.mapIndexed { i, v -> update(v, (startSec + i) * 1_000L, t) }

    private fun FocusStateMachine.focus(startSec: Int = 0): Int {
        feed(List(6) { high }, startSec)
        assertEquals(FocusState.FOCUSED, state)
        return startSec + 6
    }

    @Test
    fun `starts UNKNOWN and fails open to NOT_FOCUSED on the first sample`() {
        val machine = FocusStateMachine()
        assertEquals(FocusState.UNKNOWN, machine.state)

        machine.update(between, 0L, thresholds)
        assertEquals(FocusState.NOT_FOCUSED, machine.state)
    }

    @Test
    fun `enters FOCUSED once above T_high for entryDwellSec, not before`() {
        val states = FocusStateMachine().feed(List(6) { high })

        // Samples at 0..4 s span 4 s; the sample at 5 s completes the dwell.
        assertEquals(List(5) { FocusState.NOT_FOCUSED } + FocusState.FOCUSED, states)
    }

    @Test
    fun `leaves FOCUSED once below T_low for exitDwellSec, not before`() {
        val machine = FocusStateMachine()
        val next = machine.focus()

        val states = machine.feed(List(11) { low }, startSec = next)

        assertEquals(List(10) { FocusState.FOCUSED } + FocusState.NOT_FOCUSED, states)
    }

    @Test
    fun `a value between the thresholds restarts the entry run`() {
        val machine = FocusStateMachine()
        machine.feed(List(4) { high } + between + List(5) { high })
        assertEquals(FocusState.NOT_FOCUSED, machine.state)

        machine.update(high, 10_000L, thresholds)
        assertEquals(FocusState.FOCUSED, machine.state)
    }

    @Test
    fun `a brief dip below T_low does not end a focus period`() {
        val machine = FocusStateMachine()
        val next = machine.focus()

        machine.feed(List(8) { low } + high + List(8) { low }, startSec = next)

        assertEquals(FocusState.FOCUSED, machine.state)
    }

    @Test
    fun `values between the thresholds hold the current state`() {
        val machine = FocusStateMachine()
        val next = machine.focus()

        machine.feed(List(120) { between }, startSec = next)
        assertEquals(FocusState.FOCUSED, machine.state)

        machine.reset()
        machine.feed(List(120) { between })
        assertEquals(FocusState.NOT_FOCUSED, machine.state)
    }

    @Test
    fun `no chatter when the index oscillates around a single threshold`() {
        val machine = FocusStateMachine()
        val next = machine.focus()

        val states = machine.feed(List(60) { if (it % 2 == 0) 0.4 else 0.6 }, startSec = next)

        assertEquals(List(60) { FocusState.FOCUSED }, states)
    }

    @Test
    fun `a gap longer than maxGapMs restarts the run`() {
        val machine = FocusStateMachine(maxGapMs = 3_000L)
        machine.feed(List(4) { high })

        // 0..3 s then 10 s: the run would span 10 s, but the gap means nothing is known about it.
        machine.update(high, 10_000L, thresholds)
        assertEquals(FocusState.NOT_FOCUSED, machine.state)

        machine.feed(List(5) { high }, startSec = 11)
        assertEquals(FocusState.FOCUSED, machine.state)
    }

    @Test
    fun `a changed exit timeout applies to the run already in progress`() {
        val machine = FocusStateMachine()
        val next = machine.focus()
        machine.feed(List(4) { low }, startSec = next)
        assertEquals(FocusState.FOCUSED, machine.state)

        machine.update(low, (next + 4) * 1_000L, thresholds.copy(exitDwellSec = 3))
        assertEquals(FocusState.NOT_FOCUSED, machine.state)
    }

    @Test
    fun `zero dwell switches on the first qualifying sample`() {
        val instant = thresholds.copy(entryDwellSec = 0, exitDwellSec = 0)
        val machine = FocusStateMachine()

        assertEquals(FocusState.FOCUSED, machine.update(high, 0L, instant))
        assertEquals(FocusState.NOT_FOCUSED, machine.update(low, 1_000L, instant))
    }

    @Test
    fun `reset returns to UNKNOWN and discards runs in progress`() {
        val machine = FocusStateMachine()
        machine.feed(List(4) { high })
        machine.reset()
        assertEquals(FocusState.UNKNOWN, machine.state)

        machine.feed(List(2) { high }, startSec = 4)
        assertEquals(FocusState.NOT_FOCUSED, machine.state)
    }
}
