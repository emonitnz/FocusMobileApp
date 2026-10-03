package com.example.compx551a4.signal

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The live path with a calibration on file: epochs are averaged over a few seconds and scored
 * against the calibration run's distribution rather than a rolling window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusPipelineSmoothingTest {

    private val reference = DriftDetector.Reference(fittedAtMs = 1L, mean = 0.14, sd = 0.04)

    /** A real fit from a Muse session: thresholds only 0.16 SD apart. */
    private val thresholds = FocusThresholds(
        tHighZ = 0.23, tLowZ = 0.07, entryDwellSec = 10, exitDwellSec = 60, fittedAtMs = 1L, auc = 0.67,
    )

    private fun epoch(atSec: Int, index: Double): FocusEpoch {
        // beta held constant so the muscle check cannot fire; the index moves through alpha + theta.
        val bands = BandPowers(alphaTemporal = 0.5 / index, beta1320 = 1.0, theta = 0.5 / index, emgBroadband = 0.1)
        return FocusEpoch(atSec * 1_000L, bands.engagementIndex, bands, ContactQuality(1, 1, 1, 1), ArtifactFlags.NONE, valid = true)
    }

    private fun pipeline(smoothing: Int) = FocusPipeline(
        initialThresholds = thresholds,
        smoothingWindow = smoothing,
        drift = DriftDetector().apply { reference = this@FocusPipelineSmoothingTest.reference },
    )

    /** z alternates +1.0 / -0.3 around the calibration mean: above T_high on average, never for 10 s raw. */
    private fun noisyFocusedIndex(atSec: Int) = reference.mean + reference.sd * (if (atSec % 2 == 0) 1.0 else -0.3)

    @Test
    fun `with a calibration reference the first accepted epoch is scored, no warm-up`() = runTest(UnconfinedTestDispatcher()) {
        val p = pipeline(smoothing = 1)
        val decisions = mutableListOf<FocusDecision>()
        backgroundScope.launch { p.decisions.collect { decisions += it } }

        p.submit(epoch(0, reference.mean + reference.sd))
        assertTrue(decisions.single().accepted)
        assertTrue(abs(decisions.single().normalizedZ - 1.0) < 1e-9)
    }

    @Test
    fun `smoothing lets a noisy but genuinely focused stretch enter FOCUSED`() = runTest(UnconfinedTestDispatcher()) {
        val p = pipeline(smoothing = 8)
        for (sec in 0 until 40) p.submit(epoch(sec, noisyFocusedIndex(sec)))
        assertEquals(FocusState.FOCUSED, p.state.value)
    }

    @Test
    fun `without smoothing the same stretch never enters FOCUSED`() = runTest(UnconfinedTestDispatcher()) {
        val p = pipeline(smoothing = 1)
        for (sec in 0 until 40) p.submit(epoch(sec, noisyFocusedIndex(sec)))
        assertEquals(FocusState.NOT_FOCUSED, p.state.value)
    }

    @Test
    fun `smoothed z is reported on the decision so the chart matches the thresholds`() = runTest(UnconfinedTestDispatcher()) {
        val p = pipeline(smoothing = 8)
        val decisions = mutableListOf<FocusDecision>()
        backgroundScope.launch { p.decisions.collect { decisions += it } }
        for (sec in 0 until 16) p.submit(epoch(sec, noisyFocusedIndex(sec)))
        // Mean of +1.0 and -0.3 over an even window is 0.35.
        assertTrue(abs(decisions.last().normalizedZ - 0.35) < 1e-6)
    }

    @Test
    fun `reset clears the smoothing window`() = runTest(UnconfinedTestDispatcher()) {
        val p = pipeline(smoothing = 8)
        val decisions = mutableListOf<FocusDecision>()
        backgroundScope.launch { p.decisions.collect { decisions += it } }
        for (sec in 0 until 8) p.submit(epoch(sec, reference.mean + reference.sd * 2))
        p.reset()
        p.submit(epoch(20, reference.mean))
        assertTrue(abs(decisions.last().normalizedZ) < 1e-9)
    }
}
