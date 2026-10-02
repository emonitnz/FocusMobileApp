package com.example.compx551a4.signal

import com.example.compx551a4.core.FocusEpoch

// [B] Blink/jaw flags, poor HSI, broadband 20-40 Hz EMG, head movement.

/** Why an epoch was rejected, in the order the checks run. */
enum class RejectReason {
    /** The source marked the epoch invalid: its own blink, jaw, movement or EMG flags. */
    SOURCE_INVALID,

    /** At least one electrode reported worse than good contact. */
    POOR_CONTACT,

    /** The focus index or a band power was NaN or infinite. */
    NON_FINITE,

    /** Beta rose above [ArtifactRejector.muscleRatio] × the user's recent median — forehead EMG. */
    MUSCLE,

    /** Clean on its own, but within [ArtifactRejector.holdoffMs] of an earlier rejection. */
    HOLDOFF,
}

/**
 * The artifact gate in front of the state machine (DESIGN.md §1).
 *
 * Rejects an epoch when the source marked it invalid, when any electrode has worse than good
 * contact, when any value is non-finite, or when 13-20 Hz beta exceeds [muscleRatio] × the median of
 * this user's recent beta — a muscle burst, not concentration. A fixed beta threshold would not work
 * because absolute band power varies between users and sessions (DESIGN.md §2).
 *
 * Artifacts smear across overlapping windows, so after any rejection the epochs within the next
 * [holdoffMs] are rejected as well. Only a fresh artifact restarts the holdoff; a holdoff rejection
 * does not, or one blink would keep the gate shut forever.
 *
 * The median is taken over the last [medianWindow] epochs that passed the source, contact and
 * finiteness checks, whether or not they then failed the muscle check. Excluding muscle-rejected
 * epochs would lock the rejector out permanently if the user's baseline beta genuinely rose; with a
 * one-minute window a brief clench cannot move the median, while a lasting shift is absorbed. The
 * muscle check is skipped until [minMedianSamples] epochs are in the window.
 *
 * Not thread-safe; the owning pipeline calls it from a single coroutine.
 */
class ArtifactRejector(
    val muscleRatio: Double = DEFAULT_MUSCLE_RATIO,
    val holdoffMs: Long = DEFAULT_HOLDOFF_MS,
    private val medianWindow: Int = DEFAULT_MEDIAN_WINDOW,
    private val minMedianSamples: Int = DEFAULT_MIN_MEDIAN_SAMPLES,
) {
    private val recentBeta = ArrayDeque<Double>()

    /** Epochs with a timestamp up to and including this are rejected as [RejectReason.HOLDOFF]. */
    private var holdoffUntilMs: Long? = null

    /** Returns null when [epoch] is accepted, otherwise the reason it was rejected. */
    fun check(epoch: FocusEpoch): RejectReason? {
        val reason = primaryReason(epoch)
        if (reason != null) {
            holdoffUntilMs = epoch.timestampMs + holdoffMs
            return reason
        }
        val until = holdoffUntilMs
        if (until != null && epoch.timestampMs <= until) return RejectReason.HOLDOFF
        return null
    }

    /** Clears the median window and any holdoff — session start, re-baseline, reconnect. */
    fun reset() {
        recentBeta.clear()
        holdoffUntilMs = null
    }

    private fun primaryReason(epoch: FocusEpoch): RejectReason? {
        if (!epoch.valid) return RejectReason.SOURCE_INVALID
        if (!epoch.contact.allGood) return RejectReason.POOR_CONTACT
        if (!epoch.isFinite()) return RejectReason.NON_FINITE

        val beta = epoch.bands.beta1320
        val median = if (recentBeta.size >= minMedianSamples) median(recentBeta) else null
        recentBeta.addLast(beta)
        if (recentBeta.size > medianWindow) recentBeta.removeFirst()

        if (median != null && beta > muscleRatio * median) return RejectReason.MUSCLE
        return null
    }

    private fun FocusEpoch.isFinite(): Boolean =
        focusIndex.isFinite() &&
                // alpha + theta == 0 makes the Pope index infinite even when every band is finite.
                bands.engagementIndex.isFinite() &&
                bands.alphaTemporal.isFinite() &&
                bands.beta1320.isFinite() &&
                bands.theta.isFinite() &&
                bands.emgBroadband.isFinite()

    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    companion object {
        const val DEFAULT_MUSCLE_RATIO = 1.3
        const val DEFAULT_HOLDOFF_MS = 2_000L

        /** About a minute of epochs at ~1 Hz. */
        const val DEFAULT_MEDIAN_WINDOW = 60
        const val DEFAULT_MIN_MEDIAN_SAMPLES = 10
    }
}