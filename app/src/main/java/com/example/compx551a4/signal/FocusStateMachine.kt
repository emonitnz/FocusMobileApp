package com.example.compx551a4.signal

import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds

// [B] Two thresholds + entry/exit dwell. No chatter at the boundary.

/**
 * The Schmitt-trigger state machine from DESIGN.md §1.
 *
 * - Enter [FocusState.FOCUSED] when the index stays above `T_high` for [FocusThresholds.entryDwellSec].
 * - Enter [FocusState.NOT_FOCUSED] when it stays below `T_low` for [FocusThresholds.exitDwellSec].
 * - Anything else keeps the current state, so values between the two thresholds cannot cause chatter.
 *
 * Only accepted samples are fed in. Rejected epochs are simply not passed to [update], so a blink
 * neither ends a run nor counts towards it. "Sustained" is measured between sample timestamps: a run
 * starts at its first qualifying sample and holds once `now - start >= dwell`. If accepted samples
 * are more than [maxGapMs] apart, the run restarts, because nothing is known about the gap.
 *
 * Thresholds are passed on every call rather than held, so a changed exit timeout or a fresh
 * calibration takes effect on the next sample without resetting the runs.
 *
 * Not thread-safe; the owning pipeline calls it from a single coroutine.
 */
class FocusStateMachine(
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    var state: FocusState = FocusState.UNKNOWN
        private set

    /** Timestamp at which the current above-`T_high` run started, or null if there is none. */
    private var highSinceMs: Long? = null

    /** Timestamp at which the current below-`T_low` run started, or null if there is none. */
    private var lowSinceMs: Long? = null

    private var lastSampleMs: Long? = null

    /**
     * Feeds one accepted sample and returns the resulting state.
     *
     * @param index the normalised focus index (a z-score) compared against the thresholds.
     */
    fun update(index: Double, timestampMs: Long, thresholds: FocusThresholds): FocusState {
        val last = lastSampleMs
        if (last != null && timestampMs - last > maxGapMs) {
            highSinceMs = null
            lowSinceMs = null
        }
        lastSampleMs = timestampMs

        when {
            index > thresholds.tHighZ -> {
                if (highSinceMs == null) highSinceMs = timestampMs
                lowSinceMs = null
            }
            index < thresholds.tLowZ -> {
                if (lowSinceMs == null) lowSinceMs = timestampMs
                highSinceMs = null
            }
            else -> {
                highSinceMs = null
                lowSinceMs = null
            }
        }

        state = when {
            state != FocusState.FOCUSED &&
                    sustained(highSinceMs, timestampMs, thresholds.entryDwellSec) -> FocusState.FOCUSED
            state != FocusState.NOT_FOCUSED &&
                    sustained(lowSinceMs, timestampMs, thresholds.exitDwellSec) -> FocusState.NOT_FOCUSED
            // First usable signal: fail open until focus is actually established.
            state == FocusState.UNKNOWN -> FocusState.NOT_FOCUSED
            else -> state
        }
        return state
    }

    /** Back to [FocusState.UNKNOWN] with no runs in progress — session start, re-baseline, signal loss. */
    fun reset() {
        state = FocusState.UNKNOWN
        highSinceMs = null
        lowSinceMs = null
        lastSampleMs = null
    }

    private fun sustained(sinceMs: Long?, nowMs: Long, dwellSec: Int): Boolean =
        sinceMs != null && nowMs - sinceMs >= dwellSec * 1_000L

    companion object {
        /** Epochs arrive at ~1 Hz, so a few missing seconds means the signal was lost, not paused. */
        const val DEFAULT_MAX_GAP_MS = 5_000L
    }
}