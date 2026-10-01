package com.example.compx551a4.calibration

import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.FocusThresholds
import kotlin.math.max
import kotlin.math.sqrt

// [B] Fits T_high/T_low from high-vs-low separation. Reports AUC for the evaluation.

/**
 * One accepted calibration epoch's Pope index, labelled with the block it came from.
 *
 * [block] identifies the block within the run (0-8), not the condition, so a later
 * leave-one-block-out evaluation can hold out a whole block at a time.
 */
data class CalibrationSample(
    val condition: CalibrationCondition,
    val block: Int,
    val index: Double,
)

sealed interface FitOutcome {

    /**
     * Thresholds fitted from the run.
     *
     * [separable] is false when HIGH does not sit clearly above LOW — the index does not track
     * engagement for this user. The thresholds are still reported so the run can be recorded, but
     * the caller should not start suppressing notifications on them (DESIGN.md §7: poor
     * separability is a finding to report, not something to paper over).
     */
    data class Fitted(
        val thresholds: FocusThresholds,
        val auc: Double,
        val lowMeanZ: Double,
        val highMeanZ: Double,
        /** Pope index statistics over the run — the reference that z-scores were taken against. */
        val indexMean: Double,
        val indexSd: Double,
        val lowCount: Int,
        val highCount: Int,
        val separable: Boolean,
    ) : FitOutcome

    /** Too few accepted LOW or HIGH epochs to fit anything — e.g. the headset lost contact. */
    data class Insufficient(val lowCount: Int, val highCount: Int) : FitOutcome
}

/**
 * Fits `T_high` / `T_low` from the separation between the LOW and HIGH conditions (DESIGN.md §2).
 *
 * 1. Every sample's index, MEDIUM included, is z-scored against the whole run's mean and SD. The
 *    run is ~9 minutes, about the length of the pipeline's rolling window, so these z-scores sit on
 *    the same scale as the ones the live pipeline compares against.
 * 2. The decision point is the midpoint of the LOW and HIGH class means.
 * 3. Hysteresis: `T_high` and `T_low` sit either side of the midpoint, each [hysteresisFraction]
 *    of the class separation away from it, and never closer than [minHalfGapZ]. So `T_low < T_high`
 *    always, and the dead band scales with how far apart the classes are.
 * 4. AUC is the Mann-Whitney probability that a random HIGH epoch scores above a random LOW one
 *    (ties count half). 0.5 is chance. It is threshold-free, so it measures the index itself rather
 *    than this particular fitting rule.
 *
 * Pure and stateless.
 */
class ThresholdFitter(
    private val hysteresisFraction: Double = DEFAULT_HYSTERESIS_FRACTION,
    private val minHalfGapZ: Double = DEFAULT_MIN_HALF_GAP_Z,
    private val minSamplesPerClass: Int = DEFAULT_MIN_SAMPLES_PER_CLASS,
    private val minSeparableAuc: Double = DEFAULT_MIN_SEPARABLE_AUC,
) {
    init {
        require(hysteresisFraction in 0.0..0.5) { "hysteresisFraction must be in 0..0.5" }
        require(minHalfGapZ > 0.0) { "minHalfGapZ must be positive, or T_low could equal T_high" }
    }

    /**
     * @param entryDwellSec carried into the result unchanged; dwell times are user settings, not fitted.
     * @param exitDwellSec likewise.
     */
    fun fit(
        samples: List<CalibrationSample>,
        entryDwellSec: Int,
        exitDwellSec: Int,
        nowMs: Long,
    ): FitOutcome {
        val usable = samples.filter { it.index.isFinite() }
        val lowCount = usable.count { it.condition == CalibrationCondition.LOW }
        val highCount = usable.count { it.condition == CalibrationCondition.HIGH }
        if (lowCount < minSamplesPerClass || highCount < minSamplesPerClass) {
            return FitOutcome.Insufficient(lowCount, highCount)
        }

        val indexMean = usable.map { it.index }.average()
        val indexSd = sampleSd(usable.map { it.index }, indexMean)
        fun z(s: CalibrationSample) = if (indexSd < MIN_SD) 0.0 else (s.index - indexMean) / indexSd

        val low = usable.filter { it.condition == CalibrationCondition.LOW }.map(::z)
        val high = usable.filter { it.condition == CalibrationCondition.HIGH }.map(::z)
        val lowMean = low.average()
        val highMean = high.average()

        val midpoint = (lowMean + highMean) / 2.0
        val halfGap = max(hysteresisFraction * (highMean - lowMean), minHalfGapZ)
        val auc = auc(low = low, high = high)

        return FitOutcome.Fitted(
            thresholds = FocusThresholds(
                tHighZ = midpoint + halfGap,
                tLowZ = midpoint - halfGap,
                entryDwellSec = entryDwellSec,
                exitDwellSec = exitDwellSec,
                fittedAtMs = nowMs,
                auc = auc,
            ),
            auc = auc,
            lowMeanZ = lowMean,
            highMeanZ = highMean,
            indexMean = indexMean,
            indexSd = indexSd,
            lowCount = lowCount,
            highCount = highCount,
            separable = highMean > lowMean && auc >= minSeparableAuc,
        )
    }

    companion object {
        /** Each threshold sits 15% of the class separation from the midpoint: a 30% dead band. */
        const val DEFAULT_HYSTERESIS_FRACTION = 0.15
        const val DEFAULT_MIN_HALF_GAP_Z = 0.05

        /** ~10 s of accepted epochs per class — less than that is a failed run, not a calibration. */
        const val DEFAULT_MIN_SAMPLES_PER_CLASS = 10
        const val DEFAULT_MIN_SEPARABLE_AUC = 0.6
        private const val MIN_SD = 1e-9

        /** Mann-Whitney AUC: P(high > low) over all pairs, ties counting half. 0.5 when either is empty. */
        fun auc(low: List<Double>, high: List<Double>): Double {
            if (low.isEmpty() || high.isEmpty()) return 0.5
            var concordant = 0.0
            for (h in high) for (l in low) {
                concordant += when {
                    h > l -> 1.0
                    h == l -> 0.5
                    else -> 0.0
                }
            }
            return concordant / (low.size.toDouble() * high.size)
        }

        private fun sampleSd(values: List<Double>, mean: Double): Double =
            if (values.size < 2) 0.0
            else sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}