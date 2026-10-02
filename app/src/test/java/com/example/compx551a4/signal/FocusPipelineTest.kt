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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusPipelineTest {

    private val thresholds = FocusThresholds.PLACEHOLDER.copy(
        tHighZ = 1.0,
        tLowZ = -1.0,
        entryDwellSec = 5,
        exitDwellSec = 10,
    )

    /**
     * An epoch whose Pope index is [index]. The index is moved through alpha + theta with beta held
     * constant, so the rejector's beta-based muscle check never fires on a genuine index change.
     */
    private fun epoch(atSec: Int, index: Double, valid: Boolean = true): FocusEpoch {
        val bands = BandPowers(alphaTemporal = 0.5 / index, beta1320 = 1.0, theta = 0.5 / index, emgBroadband = 0.1)
        return FocusEpoch(
            timestampMs = atSec * 1_000L,
            focusIndex = bands.engagementIndex,
            bands = bands,
            contact = ContactQuality(1, 1, 1, 1),
            artifacts = if (valid) ArtifactFlags.NONE else ArtifactFlags(jawClench = true),
            valid = valid,
        )
    }

    /** Baseline wobbling around 1.0 so the window has a non-zero SD. Returns the next free second. */
    private fun FocusPipeline.warm(seconds: Int = 60): Int {
        repeat(seconds) { submit(epoch(it, 1.0 + (it % 3 - 1) * 0.05)) }
        return seconds
    }

    @Test
    fun `an uncalibrated pipeline stays UNKNOWN and accepts nothing`() = runTest {
        val pipeline = FocusPipeline(initialThresholds = null)
        val decisions = mutableListOf<FocusDecision>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.decisions.collect { decisions += it } }

        pipeline.warm()
        repeat(30) { pipeline.submit(epoch(60 + it, 3.0)) }

        assertEquals(FocusState.UNKNOWN, pipeline.state.value)
        assertEquals(90, decisions.size)
        assertTrue(decisions.none { it.accepted })
    }

    @Test
    fun `the window keeps warming while uncalibrated, so calibration takes effect immediately`() = runTest {
        val pipeline = FocusPipeline(initialThresholds = null)
        val t = pipeline.warm()

        pipeline.updateThresholds(thresholds)
        pipeline.submit(epoch(t, 1.0))

        assertEquals(FocusState.NOT_FOCUSED, pipeline.state.value)
    }

    @Test
    fun `decisions are not accepted while the window warms up`() = runTest {
        val pipeline = FocusPipeline(thresholds)
        val decisions = mutableListOf<FocusDecision>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.decisions.collect { decisions += it } }

        pipeline.warm(RollingZScore.DEFAULT_WARMUP + 1)

        assertTrue(decisions.dropLast(1).none { it.accepted })
        assertTrue(decisions.last().accepted)
        assertEquals(FocusState.UNKNOWN, decisions[RollingZScore.DEFAULT_WARMUP - 1].state)
    }

    @Test
    fun `a sustained rise in the index enters FOCUSED and a sustained fall leaves it`() = runTest {
        val pipeline = FocusPipeline(thresholds)
        var t = pipeline.warm()
        assertEquals(FocusState.NOT_FOCUSED, pipeline.state.value)

        repeat(10) { pipeline.submit(epoch(t++, 2.0)) }
        assertEquals(FocusState.FOCUSED, pipeline.state.value)

        repeat(15) { pipeline.submit(epoch(t++, 0.5)) }
        assertEquals(FocusState.NOT_FOCUSED, pipeline.state.value)
    }

    @Test
    fun `decisions carry the Pope index and its z-score`() = runTest {
        val pipeline = FocusPipeline(thresholds)
        val decisions = mutableListOf<FocusDecision>()
        val t = pipeline.warm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.decisions.collect { decisions += it } }

        pipeline.submit(epoch(t, 2.0))

        val d = decisions.single()
        assertEquals(2.0, d.rawIndex, 1e-9)
        assertTrue("well above a baseline around 1.0", d.normalizedZ > 10.0)
        assertTrue(d.accepted)
    }

    @Test
    fun `rejected epochs are reported but neither change state nor enter the window`() = runTest {
        val pipeline = FocusPipeline(thresholds)
        val decisions = mutableListOf<FocusDecision>()
        var t = pipeline.warm()
        repeat(10) { pipeline.submit(epoch(t++, 2.0)) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.decisions.collect { decisions += it } }

        repeat(20) { pipeline.submit(epoch(t++, 0.1, valid = false)) }

        assertEquals(FocusState.FOCUSED, pipeline.state.value)
        assertEquals(20, decisions.size)
        assertTrue(decisions.all { !it.accepted && it.state == FocusState.FOCUSED })
    }

    @Test
    fun `reset returns to UNKNOWN and re-warms from scratch`() = runTest {
        val pipeline = FocusPipeline(thresholds)
        val t = pipeline.warm()

        pipeline.reset()
        assertEquals(FocusState.UNKNOWN, pipeline.state.value)

        pipeline.submit(epoch(t, 1.0))
        assertEquals("window must warm up again", FocusState.UNKNOWN, pipeline.state.value)
    }

    @Test
    fun `new thresholds apply to the next epoch`() = runTest {
        val pipeline = FocusPipeline(thresholds)
        var t = pipeline.warm()

        pipeline.updateThresholds(thresholds.copy(entryDwellSec = 0))
        pipeline.submit(epoch(t, 2.0))

        assertEquals(FocusState.FOCUSED, pipeline.state.value)
    }
}

class RollingZScoreTest {

    @Test
    fun `returns null until warmed up`() {
        val z = RollingZScore(windowSize = 10, warmup = 3)
        assertNull(z.push(1.0))
        assertNull(z.push(2.0))
        assertNull(z.push(3.0))
        assertTrue(z.push(4.0) != null)
    }

    @Test
    fun `scores against the window before adding the value`() {
        val z = RollingZScore(windowSize = 10, warmup = 3)
        listOf(1.0, 2.0, 3.0).forEach { z.push(it) }

        // mean 2, sample SD 1
        assertEquals(2.0, z.push(4.0)!!, 1e-9)
    }

    @Test
    fun `old values roll out of the window`() {
        val z = RollingZScore(windowSize = 3, warmup = 3)
        listOf(100.0, 1.0, 2.0, 3.0).forEach { z.push(it) }

        assertEquals(3, z.size)
        assertEquals(2.0, z.push(4.0)!!, 1e-9)
    }

    @Test
    fun `a flat window scores zero instead of dividing by zero`() {
        val z = RollingZScore(windowSize = 10, warmup = 3)
        repeat(5) { z.push(1.0) }

        assertEquals(0.0, z.push(5.0)!!, 0.0)
    }

    @Test
    fun `reset empties the window`() {
        val z = RollingZScore(windowSize = 10, warmup = 3)
        repeat(5) { z.push(1.0) }
        z.reset()

        assertEquals(0, z.size)
        assertFalse(z.push(1.0) != null)
    }
}
