package com.example.compx551a4.ui

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.ui.preview.DemoFocusEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo engine is a stand-in for workload B, not the research contribution. These tests are
 * written against the *contract* rather than the implementation, so they should still pass — and
 * are worth keeping — once B's FocusPipeline replaces it.
 */
class DemoFocusEngineTest {

    private fun epoch(index: Double, valid: Boolean = true, at: Long = 0L) = FocusEpoch(
        timestampMs = at,
        focusIndex = index,
        bands = BandPowers(1.0, index * 2.0, 1.0, 0.1),
        contact = ContactQuality(1, 1, 1, 1),
        artifacts = ArtifactFlags.NONE,
        valid = valid,
    )

    @Test
    fun `an uncalibrated engine never claims to know the focus state`() = runTest {
        val engine = DemoFocusEngine(initialThresholds = null)
        repeat(200) { engine.submit(epoch(2.0, at = it.toLong())) }

        assertNull(engine.thresholds.value)
        assertEquals(FocusState.UNKNOWN, engine.state.value)
    }

    @Test
    fun `sustained high focus eventually enters FOCUSED, and a single spike does not`() = runTest {
        val engine = DemoFocusEngine(FocusThresholds.PLACEHOLDER.copy(entryDwellSec = 5))
        // Warm the rolling window up on a flat-ish baseline.
        repeat(60) { engine.submit(epoch(1.0 + (it % 3) * 0.05, at = it.toLong())) }

        engine.submit(epoch(4.0, at = 100L))
        assertEquals("one spike must not silence the phone", FocusState.NOT_FOCUSED, engine.state.value)

        repeat(20) { engine.submit(epoch(4.0, at = 200L + it)) }
        assertEquals(FocusState.FOCUSED, engine.state.value)
    }

    @Test
    fun `fitting separates cleanly labelled low and high blocks`() = runTest {
        val engine = DemoFocusEngine()
        val blocks = listOf(
            CalibrationBlock(CalibrationCondition.LOW, startedAtMs = 0L, durationMs = 1_000L),
            CalibrationBlock(CalibrationCondition.HIGH, startedAtMs = 2_000L, durationMs = 1_000L),
        )
        val epochs = List(20) { epoch(1.0, at = it.toLong() * 50) } +
            List(20) { epoch(3.0, at = 2_000L + it * 50) }

        val result = engine.fitCalibration(blocks, epochs)

        assertEquals("perfectly separated classes should score 1.0", 1.0, result.auc, 0.001)
        assertTrue(result.highMeanZ > result.lowMeanZ)
        assertTrue(result.thresholds.tHighZ > result.thresholds.tLowZ)
        assertEquals(result.thresholds, engine.thresholds.value)
    }

    @Test
    fun `fitting on an empty run reports chance rather than crashing`() = runTest {
        val result = DemoFocusEngine().fitCalibration(emptyList(), emptyList())
        assertEquals(0.5, result.auc, 0.001)
    }
}
