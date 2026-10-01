package com.example.compx551a4.signal

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition.HIGH
import com.example.compx551a4.core.CalibrationCondition.LOW
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.core.TaskScore
import com.example.compx551a4.data.ThresholdStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriftDetectorTest {

    private val ref = DriftDetector.Reference(fittedAtMs = 7L, mean = 1.0, sd = 0.5)

    private fun detector() = DriftDetector(minSamples = 10, sustainSamples = 3).also { it.reference = ref }

    /** Feeds the same window stats [n] times and returns the last verdict. */
    private fun DriftDetector.feed(n: Int, mean: Double, sd: Double, size: Int = 100, fittedAt: Long = 7L): Boolean =
        (1..n).map { check(mean, sd, size, fittedAt) }.last()

    @Test
    fun `no drift while the running distribution matches the reference`() {
        assertFalse(detector().feed(100, mean = 1.2, sd = 0.6))
    }

    @Test
    fun `a mean shift beyond the limit is reported once sustained`() {
        val d = detector()
        assertFalse(d.feed(2, mean = 1.6, sd = 0.5)) // 1.2 reference SDs, not yet sustained
        assertTrue(d.feed(1, mean = 1.6, sd = 0.5))
    }

    @Test
    fun `a spread change in either direction is drift`() {
        assertTrue(detector().feed(3, mean = 1.0, sd = 1.1))
        assertTrue(detector().feed(3, mean = 1.0, sd = 0.2))
    }

    @Test
    fun `a single recovered check restarts the count`() {
        val d = detector()
        d.feed(2, mean = 2.0, sd = 0.5)
        d.feed(1, mean = 1.0, sd = 0.5)

        assertFalse(d.feed(2, mean = 2.0, sd = 0.5))
        assertTrue(d.feed(1, mean = 2.0, sd = 0.5))
    }

    @Test
    fun `nothing is judged on a short window`() {
        assertFalse(detector().feed(100, mean = 5.0, sd = 0.5, size = 9))
    }

    @Test
    fun `thresholds from a different fit are not compared against this reference`() {
        assertFalse(detector().feed(100, mean = 5.0, sd = 0.5, fittedAt = 8L))
    }

    @Test
    fun `no reference means no drift`() {
        val d = DriftDetector(minSamples = 10, sustainSamples = 3)
        assertFalse(d.feed(100, mean = 5.0, sd = 0.5))
    }

    @Test
    fun `reset forgets drift in progress`() {
        val d = detector()
        d.feed(2, mean = 2.0, sd = 0.5)
        d.reset()

        assertFalse(d.feed(2, mean = 2.0, sd = 0.5))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FocusPipelineDriftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun epoch(atSec: Int, index: Double): FocusEpoch {
        val bands = BandPowers(alphaTemporal = 0.5 / index, beta1320 = 1.0, theta = 0.5 / index, emgBroadband = 0.1)
        return FocusEpoch(atSec * 1_000L, bands.engagementIndex, bands, ContactQuality(1, 1, 1, 1), ArtifactFlags.NONE, true)
    }

    /** Two LOW blocks around 0.5 and two HIGH around 1.5: reference mean ≈ 1.0, SD ≈ 0.5. */
    private suspend fun FocusPipeline.calibrate() {
        val blocks = (0 until 4).map { i ->
            CalibrationBlock(if (i % 2 == 0) LOW else HIGH, i * 70_000L, 60_000L, if (i % 2 == 0) null else TaskScore(20, 25))
        }
        val epochs = blocks.flatMap { b ->
            val centre = if (b.condition == LOW) 0.5 else 1.5
            (0..60).map { s -> epoch((b.startedAtMs / 1_000).toInt() + s, centre + (s % 5 - 2) * 0.01) }
        }
        fitCalibration(blocks, epochs)
    }

    /** Values alternating ±[half] around [centre], so the window SD is about [half]. */
    private fun FocusPipeline.stream(fromSec: Int, seconds: Int, centre: Double, half: Double): Int {
        repeat(seconds) { submit(epoch(fromSec + it, centre + if (it % 2 == 0) half else -half)) }
        return fromSec + seconds
    }

    private fun pipeline(store: ThresholdStore? = null, scope: kotlinx.coroutines.CoroutineScope? = null) =
        FocusPipeline(
            store = store,
            scope = scope,
            drift = DriftDetector(minSamples = 100, sustainSamples = 20),
            normalizer = RollingZScore(windowSize = 200, warmup = 30),
        )

    @Test
    fun `a stable signal never prompts recalibration`() = runTest {
        val p = pipeline()
        p.calibrate()

        p.stream(10_000, 1_000, centre = 1.0, half = 0.5)

        assertFalse(p.recalibrationSuggested.value)
    }

    @Test
    fun `a sustained shift prompts recalibration, and it stays up`() = runTest {
        val p = pipeline()
        p.calibrate()

        var t = p.stream(10_000, 400, centre = 2.5, half = 0.5)
        assertTrue(p.recalibrationSuggested.value)

        // Back to normal, and a new session: the stale thresholds are still in use.
        p.reset()
        t = p.stream(t, 400, centre = 1.0, half = 0.5)
        assertTrue(p.recalibrationSuggested.value)
    }

    @Test
    fun `a fresh separable calibration clears the prompt`() = runTest {
        val p = pipeline()
        p.calibrate()
        p.stream(10_000, 400, centre = 2.5, half = 0.5)

        p.calibrate()

        assertFalse(p.recalibrationSuggested.value)
    }

    @Test
    fun `placeholder thresholds are never judged for drift`() = runTest {
        val p = pipeline()
        p.updateThresholds(FocusThresholds.PLACEHOLDER)

        p.stream(10_000, 400, centre = 2.5, half = 0.5)

        assertFalse(p.recalibrationSuggested.value)
    }

    @Test
    fun `editing the exit timeout keeps the drift reference`() = runTest {
        val p = pipeline()
        p.calibrate()
        p.updateThresholds(p.thresholds.value!!.copy(exitDwellSec = 200))

        p.stream(10_000, 400, centre = 2.5, half = 0.5)

        assertTrue(p.recalibrationSuggested.value)
    }

    @Test
    fun `the reference is restored from the store at startup`() = runTest {
        val store = ThresholdStore(tmp.root, UnconfinedTestDispatcher(testScheduler))
        pipeline(store).calibrate()

        val restarted = pipeline(store, scope = this)
        testScheduler.advanceUntilIdle()
        restarted.stream(10_000, 400, centre = 2.5, half = 0.5)

        assertEquals(store.loadCurrent(), restarted.thresholds.value)
        assertTrue(restarted.recalibrationSuggested.value)
    }
}
