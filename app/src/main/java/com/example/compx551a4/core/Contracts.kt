package com.example.compx551a4.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// [SHARED] The interfaces joining the four modules: EegSource (A), FocusEngine (B), Suppressor (C), SessionRecorder (D).
//
// Sprint-0 draft by workload D. Everyone codes against fakes for the interfaces they do not own.
//
// NOTE FOR RATIFICATION: WORKLOADS.md names four interfaces. This draft adds two more —
// ServiceController (A) and NotificationPolicy (C) — because the UI needs a master switch and a
// settings surface, and neither fits the original four. They are small and belong to workloads that
// already own the underlying code. Flagged here rather than assumed.

/**
 * Workload A. Owns the headset: BLE via LibMuse, or Mind Monitor over OSC while the SDK integration
 * proceeds in parallel.
 *
 * [epochs] emits at roughly 1 Hz from 2-4 s windows, not per sample. Implementations emit epochs
 * with `valid = false` rather than dropping them, so consumers can tell "bad data" from "no data".
 */
interface EegSource {
    val status: StateFlow<DeviceStatus>
    val epochs: Flow<FocusEpoch>

    suspend fun start()
    fun stop()
}

/**
 * Workload B. The research contribution: rolling normalisation and the two-threshold Schmitt-trigger
 * state machine.
 *
 * [thresholds] is null until the user has calibrated; consumers must treat that as "not ready" and
 * fail open rather than falling back to a default. [decisions] carries every epoch's outcome,
 * including rejected ones, so the UI can show what the machine is actually seeing.
 */
interface FocusEngine {
    val state: StateFlow<FocusState>
    val decisions: Flow<FocusDecision>
    val thresholds: StateFlow<FocusThresholds?>

    fun submit(epoch: FocusEpoch)
    suspend fun updateThresholds(thresholds: FocusThresholds)

    /** Clears the rolling window and dwell timers — used at session start and after re-baselining. */
    fun reset()

    /** Fits thresholds from a completed calibration run. Persists on success. */
    suspend fun fitCalibration(blocks: List<CalibrationBlock>, epochs: List<FocusEpoch>): CalibrationResult

    /**
     * True when the running distribution has shifted far enough from the fitted one that the
     * thresholds are suspect. Drives the recalibration prompt in DESIGN.md §2.
     */
    val recalibrationSuggested: StateFlow<Boolean>
}

/**
 * Workload C. Applies a focus state to the platform: AutomaticZenRule in v1, snooze-based true
 * deferral as a stretch.
 *
 * Every implementation must fail open — DESIGN.md §3: headset off, BLE dropped, battery dead or poor
 * contact all deliver notifications normally. [apply] with [FocusState.UNKNOWN] means exactly that.
 */
interface Suppressor {
    val deferred: StateFlow<List<DeferredNotification>>

    /**
     * Called for every decision, so roughly once a second — implementations must be idempotent and
     * cheap, and should not touch the platform unless the state actually changed.
     */
    suspend fun apply(state: FocusState)

    /** Releases the queue as a single grouped summary, never a burst of heads-up notifications. */
    suspend fun flush(reason: FlushReason)
}

/**
 * Workload D. Written to from the service's coroutine scope at roughly 1 Hz, so implementations
 * batch rather than doing file I/O per decision.
 */
interface SessionRecorder {
    val stats: Flow<SessionStats>

    suspend fun onSessionStart(atMs: Long)
    suspend fun onDecision(decision: FocusDecision)
    suspend fun onDeferred(notification: DeferredNotification)
    suspend fun onFlush(count: Int, reason: FlushReason, atMs: Long)
    suspend fun onSessionEnd(atMs: Long)
}

/**
 * Workload A (proposed addition). Backs the master switch on the home screen.
 *
 * The UI must not start the foreground service with a raw Intent, because it also needs to observe
 * whether the service survived Doze and the OEM battery killers — DESIGN.md §5 says to expect
 * exactly that during demos.
 */
interface ServiceController {
    val running: StateFlow<Boolean>

    fun start()
    fun stop()
}

/**
 * Workload C (proposed addition). Backs the notification section of the settings screen: the
 * breakthrough allowlist and the max-hold safety valve.
 */
interface NotificationPolicy {
    val settings: StateFlow<PolicySettings>

    suspend fun update(settings: PolicySettings)
}
