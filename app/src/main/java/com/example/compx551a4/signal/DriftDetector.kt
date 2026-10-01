package com.example.compx551a4.signal

import kotlin.math.abs

// [B] Recalibration prompt: has the running index distribution moved away from the fitted one?

/**
 * Decides when the thresholds have gone stale (DESIGN.md §2: electrode placement, hair, skin
 * moisture, headband tension, time of day and caffeine all shift the signal between sessions).
 *
 * The reference is the Pope index's mean and SD over the calibration run. The running distribution
 * is the pipeline's rolling window. Drift is either:
 *
 * - **Location:** the running mean has moved more than [maxMeanShiftSd] reference SDs.
 * - **Spread:** the running SD is more than [maxSdRatio] times, or less than 1/[maxSdRatio] of,
 *   the reference SD. This matters even though the pipeline re-centres continuously: the
 *   thresholds are z-scores, so if the spread changes, the same z means a different raw separation
 *   from the one that was fitted.
 *
 * Nothing is judged until the window holds [minSamples] values, and drift must persist for
 * [sustainSamples] consecutive checks, so a few noisy minutes do not nag the user. Once [check]
 * reports drift it is up to the caller to keep the prompt up; the detector only says "now".
 *
 * Not thread-safe; the owning pipeline calls it under its lock.
 */
class DriftDetector(
    private val maxMeanShiftSd: Double = DEFAULT_MAX_MEAN_SHIFT_SD,
    private val maxSdRatio: Double = DEFAULT_MAX_SD_RATIO,
    private val minSamples: Int = DEFAULT_MIN_SAMPLES,
    private val sustainSamples: Int = DEFAULT_SUSTAIN_SAMPLES,
) {
    init {
        require(maxSdRatio > 1.0) { "maxSdRatio must be above 1" }
        require(sustainSamples >= 1) { "sustainSamples must be at least 1" }
    }

    /** The distribution the current thresholds were fitted on, tagged with their fit time. */
    data class Reference(val fittedAtMs: Long, val mean: Double, val sd: Double)

    var reference: Reference? = null
        set(value) {
            field = value
            driftingFor = 0
        }

    private var driftingFor = 0

    /**
     * Feeds one check, normally once per accepted epoch. Returns true once drift has been sustained.
     *
     * @param fittedAtMs the fit time of the thresholds in use. If it does not match [reference] —
     *   thresholds edited in from elsewhere, such as the developer placeholder — there is nothing
     *   valid to compare against and nothing is reported.
     */
    fun check(windowMean: Double, windowSd: Double, windowSize: Int, fittedAtMs: Long): Boolean {
        val ref = reference
        if (ref == null || ref.fittedAtMs != fittedAtMs || ref.sd <= 0.0 || windowSize < minSamples) {
            driftingFor = 0
            return false
        }

        val meanShift = abs(windowMean - ref.mean) / ref.sd
        val sdRatio = windowSd / ref.sd
        val drifted = meanShift > maxMeanShiftSd || sdRatio > maxSdRatio || sdRatio < 1.0 / maxSdRatio

        driftingFor = if (drifted) driftingFor + 1 else 0
        return driftingFor >= sustainSamples
    }

    /** Forgets any drift in progress — the window it was measured on has been cleared. */
    fun reset() {
        driftingFor = 0
    }

    companion object {
        const val DEFAULT_MAX_MEAN_SHIFT_SD = 1.0
        const val DEFAULT_MAX_SD_RATIO = 2.0

        /** Five minutes at ~1 Hz — half the rolling window, enough for a stable mean and SD. */
        const val DEFAULT_MIN_SAMPLES = 300

        /** Two minutes of continuous drift before prompting. */
        const val DEFAULT_SUSTAIN_SAMPLES = 120
    }
}