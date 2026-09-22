package com.example.compx551a4.core

// [SHARED] All shared data classes: FocusEpoch, FocusState, FocusThresholds, CalibrationBlock, DeferredNotification, SessionStats.
//
// Sprint-0 draft by workload D, shaped by what the UI has to render. See DESIGN.md for the
// reasoning behind the field choices — in particular §1 (band limits) and §2 (z-scores, not µV²).

// ---------------------------------------------------------------------------
// Signal
// ---------------------------------------------------------------------------

/**
 * Band powers for one epoch, already reduced to the four numbers the focus index needs.
 *
 * The names carry DESIGN.md §1's constraints so they cannot be quietly violated downstream:
 * alpha comes from the temporal pair (TP9/TP10), which is less eye-contaminated than the frontal
 * pair, and beta is clamped to 13-20 Hz because 13-30 Hz at a forehead electrode is substantially
 * frontalis/temporalis muscle. [emgBroadband] is the 20-40 Hz power used to catch the case where
 * beta rises because the user clenched, not because they concentrated.
 */
data class BandPowers(
    val alphaTemporal: Double,
    val beta1320: Double,
    val theta: Double,
    val emgBroadband: Double,
) {
    /** Pope et al. (1995) engagement index. */
    val engagementIndex: Double
        get() = beta1320 / (alphaTemporal + theta)
}

/** Horseshoe Indicator per electrode, 1 (good) to 4 (no contact), as LibMuse reports it. */
data class ContactQuality(
    val tp9: Int,
    val af7: Int,
    val af8: Int,
    val tp10: Int,
) {
    val worst: Int get() = maxOf(tp9, af7, af8, tp10)
    val allGood: Boolean get() = worst <= GOOD_HSI
    val asList: List<Int> get() = listOf(tp9, af7, af8, tp10)

    companion object {
        const val GOOD_HSI = 1
        const val NO_CONTACT = 4
        val UNKNOWN = ContactQuality(NO_CONTACT, NO_CONTACT, NO_CONTACT, NO_CONTACT)
    }
}

/** DESIGN.md §1's four artifact rejection rules, one flag each. */
data class ArtifactFlags(
    val blink: Boolean = false,
    val jawClench: Boolean = false,
    val movement: Boolean = false,
    val emgBroadband: Boolean = false,
) {
    val any: Boolean get() = blink || jawClench || movement || emgBroadband

    companion object {
        val NONE = ArtifactFlags()
    }
}

/**
 * One analysis window (2-4 s, 50-75% overlap) emitted by [EegSource] at roughly 1 Hz.
 *
 * [valid] is false when the epoch failed artifact rejection or contact was poor; invalid epochs are
 * still emitted so the UI can show a gap rather than silently interpolating over it.
 */
data class FocusEpoch(
    val timestampMs: Long,
    val focusIndex: Double,
    val bands: BandPowers,
    val contact: ContactQuality,
    val artifacts: ArtifactFlags,
    val valid: Boolean,
)

// ---------------------------------------------------------------------------
// Focus state
// ---------------------------------------------------------------------------

enum class FocusState {
    /** No usable signal — headset off, disconnected, or not yet settled. Suppression is off. */
    UNKNOWN,
    FOCUSED,
    NOT_FOCUSED,
}

/**
 * The state machine's output for one epoch.
 *
 * [normalizedZ] is the rolling z-score that is actually compared against the thresholds, and is what
 * the live chart plots. [accepted] is false whenever the epoch fed no decision — artifact-rejected,
 * or the rolling window still warming up. Those are drawn as gaps in the chart and do not advance
 * the dwell timers; [normalizedZ] is meaningless when it is false.
 */
data class FocusDecision(
    val timestampMs: Long,
    val state: FocusState,
    val rawIndex: Double,
    val normalizedZ: Double,
    val accepted: Boolean,
)

/**
 * Per-user fitted thresholds. Stored as z-scores, never raw µV² — DESIGN.md §2: absolute band power
 * drifts with electrode placement, hair, skin moisture, headband tension, time of day and caffeine,
 * so a raw number calibrated on Tuesday is wrong on Thursday.
 *
 * Two thresholds, not one, to stop chatter at the boundary. [exitDwellSec] is the user-adjustable
 * timeout that lets brief pauses pass without releasing the queue.
 */
data class FocusThresholds(
    val tHighZ: Double,
    val tLowZ: Double,
    val entryDwellSec: Int,
    val exitDwellSec: Int,
    val fittedAtMs: Long,
    val auc: Double,
) {
    companion object {
        /** Used before the first calibration, and by previews. Not fitted to anyone. */
        val PLACEHOLDER = FocusThresholds(
            tHighZ = 0.5,
            tLowZ = -0.2,
            entryDwellSec = 10,
            exitDwellSec = 60,
            fittedAtMs = 0L,
            auc = 0.0,
        )
    }
}

// ---------------------------------------------------------------------------
// Calibration — the B/D seam
// ---------------------------------------------------------------------------

enum class CalibrationCondition { LOW, MEDIUM, HIGH }

/**
 * Task performance for one block, logged by D's task screens. DESIGN.md §2 wants this as objective
 * confirmation that the user actually engaged, rather than relying on self-report.
 *
 * The LOW condition has no task, so its blocks carry a null score.
 */
data class TaskScore(
    val correct: Int,
    val total: Int,
    val falseAlarms: Int = 0,
    val meanReactionMs: Double? = null,
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

/**
 * One ~60 s calibration block. D fills [taskScore]; B consumes the blocks and fits the thresholds.
 * This is the single point where the two workloads meet.
 */
data class CalibrationBlock(
    val condition: CalibrationCondition,
    val startedAtMs: Long,
    val durationMs: Long,
    val taskScore: TaskScore? = null,
)

/** B's fitting output, rendered by D's result screen. */
data class CalibrationResult(
    val thresholds: FocusThresholds,
    val auc: Double,
    val blocks: List<CalibrationBlock>,
    val lowMeanZ: Double,
    val highMeanZ: Double,
)

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

data class DeferredNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val postedAtMs: Long,
    val breakthrough: Boolean = false,
)

/** Why the queue was released. Recorded so the stats screen can distinguish the safety valves. */
enum class FlushReason {
    FOCUS_BROKE,
    MAX_HOLD_EXCEEDED,
    HEADSET_REMOVED,
    SERVICE_STOPPED,
    MANUAL,
}

/** The breakthrough allowlist and the max-hold safety valve, edited on D's settings screen. */
data class PolicySettings(
    val maxHoldMinutes: Int = 30,
    val allowCalls: Boolean = true,
    val allowAlarms: Boolean = true,
    val allowRepeatCallers: Boolean = true,
    val allowStarredContacts: Boolean = true,
    val allowTwoFactorCodes: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
)

// ---------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------

enum class DeviceState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,

    /** Connected, but HSI is bad enough that decisions are not trustworthy. Fails open. */
    POOR_CONTACT,
}

data class DeviceStatus(
    val state: DeviceState,
    val deviceName: String? = null,
    val batteryPct: Int? = null,
    val contact: ContactQuality = ContactQuality.UNKNOWN,
) {
    val usable: Boolean get() = state == DeviceState.CONNECTED

    companion object {
        val DISCONNECTED = DeviceStatus(DeviceState.DISCONNECTED)
    }
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

/**
 * [focusedMsByHour] is a 24-element list indexed by local hour of day. Carried per session rather
 * than only in aggregate so the time-of-day pattern in DESIGN.md §4 survives deleting old sessions.
 */
data class SessionSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val focusedMs: Long,
    val longestStreakMs: Long,
    val deferredCount: Int,
    val deliveredCount: Int,
    val flushCount: Int,
    val focusedMsByHour: List<Long> = List(24) { 0L },
) {
    val durationMs: Long get() = endedAtMs - startedAtMs
}

data class SessionStats(
    val sessions: List<SessionSummary> = emptyList(),
) {
    val totalFocusedMs: Long get() = sessions.sumOf { it.focusedMs }
    val longestStreakMs: Long get() = sessions.maxOfOrNull { it.longestStreakMs } ?: 0L
    val totalDeferred: Int get() = sessions.sumOf { it.deferredCount }
    val totalDelivered: Int get() = sessions.sumOf { it.deliveredCount }
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Summed across sessions, indexed by local hour of day. */
    val focusedMsByHour: List<Long>
        get() = List(24) { hour -> sessions.sumOf { it.focusedMsByHour.getOrElse(hour) { 0L } } }
}
