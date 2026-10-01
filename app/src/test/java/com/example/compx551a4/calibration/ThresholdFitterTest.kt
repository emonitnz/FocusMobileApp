package com.example.compx551a4.calibration

import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.CalibrationCondition.HIGH
import com.example.compx551a4.core.CalibrationCondition.LOW
import com.example.compx551a4.core.CalibrationCondition.MEDIUM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ThresholdFitterTest {

    private val fitter = ThresholdFitter()

    private fun block(condition: CalibrationCondition, block: Int, values: List<Double>) =
        values.map { CalibrationSample(condition, block, it) }

    /** Three blocks per condition, 60 samples each, normally distributed around the given means. */
    private fun run(lowMean: Double, highMean: Double, sd: Double = 0.1, seed: Int = 1): List<CalibrationSample> {
        val rnd = Random(seed)
        fun gauss(mean: Double) = mean + sd * (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 2
        return (0 until 3).flatMap { b ->
            block(LOW, b * 3, List(60) { gauss(lowMean) }) +
                block(MEDIUM, b * 3 + 1, List(60) { gauss((lowMean + highMean) / 2) }) +
                block(HIGH, b * 3 + 2, List(60) { gauss(highMean) })
        }
    }

    private fun fitted(samples: List<CalibrationSample>, f: ThresholdFitter = fitter) =
        f.fit(samples, entryDwellSec = 10, exitDwellSec = 60, nowMs = 123L) as FitOutcome.Fitted

    @Test
    fun `T_low is below T_high, either side of the midpoint of the class means`() {
        val r = fitted(run(lowMean = 0.4, highMean = 0.8))

        assertTrue(r.thresholds.tLowZ < r.thresholds.tHighZ)
        val midpoint = (r.lowMeanZ + r.highMeanZ) / 2
        assertEquals(midpoint, (r.thresholds.tHighZ + r.thresholds.tLowZ) / 2, 1e-9)
        assertTrue(r.lowMeanZ < r.thresholds.tLowZ)
        assertTrue(r.thresholds.tHighZ < r.highMeanZ)
    }

    @Test
    fun `the dead band is the configured fraction of the class separation`() {
        val r = fitted(run(lowMean = 0.4, highMean = 0.8))

        val separation = r.highMeanZ - r.lowMeanZ
        assertEquals(2 * 0.15 * separation, r.thresholds.tHighZ - r.thresholds.tLowZ, 1e-9)
    }

    @Test
    fun `hysteresis never collapses, even when the classes overlap completely`() {
        val r = fitted(run(lowMean = 0.5, highMean = 0.5))

        assertTrue(r.thresholds.tHighZ - r.thresholds.tLowZ >= 2 * ThresholdFitter.DEFAULT_MIN_HALF_GAP_Z - 1e-12)
        assertFalse(r.separable)
    }

    @Test
    fun `thresholds are z-scores, independent of the index's absolute scale`() {
        val a = fitted(run(lowMean = 0.4, highMean = 0.8))
        val b = fitted(run(lowMean = 4.0, highMean = 8.0, sd = 1.0))

        assertEquals(a.thresholds.tHighZ, b.thresholds.tHighZ, 1e-9)
        assertEquals(a.thresholds.tLowZ, b.thresholds.tLowZ, 1e-9)
        assertEquals(a.auc, b.auc, 1e-12)
    }

    @Test
    fun `perfectly separated classes score AUC 1 and are separable`() {
        val r = fitted(run(lowMean = 0.2, highMean = 1.0, sd = 0.05))

        assertEquals(1.0, r.auc, 1e-12)
        assertTrue(r.separable)
        assertEquals(r.auc, r.thresholds.auc, 0.0)
    }

    @Test
    fun `an index that goes the wrong way is reported as not separable`() {
        val r = fitted(run(lowMean = 0.8, highMean = 0.4))

        assertTrue(r.auc < 0.5)
        assertFalse(r.separable)
        assertTrue("hysteresis still holds", r.thresholds.tLowZ < r.thresholds.tHighZ)
    }

    @Test
    fun `AUC counts ties as half`() {
        assertEquals(0.5, ThresholdFitter.auc(low = listOf(1.0, 1.0), high = listOf(1.0)), 0.0)
        assertEquals(0.75, ThresholdFitter.auc(low = listOf(0.0, 1.0), high = listOf(1.0)), 0.0)
        assertEquals(0.5, ThresholdFitter.auc(low = emptyList(), high = listOf(1.0)), 0.0)
    }

    @Test
    fun `MEDIUM shapes the normalisation but not the class means`() {
        val base = run(lowMean = 0.4, highMean = 0.8)
        val noMedium = fitted(base.filter { it.condition != MEDIUM })
        val withMedium = fitted(base)

        // Same raw separation, different reference distribution: z-scores differ, AUC does not.
        assertEquals(noMedium.auc, withMedium.auc, 1e-12)
        assertTrue(withMedium.indexSd < noMedium.indexSd)
    }

    @Test
    fun `dwell times are carried through, not fitted`() {
        val r = fitter.fit(run(0.4, 0.8), entryDwellSec = 7, exitDwellSec = 90, nowMs = 42L) as FitOutcome.Fitted

        assertEquals(7, r.thresholds.entryDwellSec)
        assertEquals(90, r.thresholds.exitDwellSec)
        assertEquals(42L, r.thresholds.fittedAtMs)
    }

    @Test
    fun `too few samples in a class is reported, not fitted`() {
        val samples = block(LOW, 0, List(50) { 0.4 }) + block(HIGH, 2, List(5) { 0.8 })

        val outcome = fitter.fit(samples, 10, 60, 0L)

        assertEquals(FitOutcome.Insufficient(lowCount = 50, highCount = 5), outcome)
    }

    @Test
    fun `non-finite indices are ignored`() {
        val clean = run(0.4, 0.8)
        val dirty = clean + block(HIGH, 2, listOf(Double.NaN, Double.POSITIVE_INFINITY))

        assertEquals(fitted(clean), fitted(dirty))
    }
}
