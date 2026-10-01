package com.example.compx551a4.signal

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FocusPipelineCalibrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ThresholdStore(tmp.root, UnconfinedTestDispatcher())

    private val blockMs = 60_000L
    private val engaged = TaskScore(correct = 20, total = 25)

    private fun epoch(atMs: Long, index: Double): FocusEpoch {
        val bands = BandPowers(alphaTemporal = 0.5 / index, beta1320 = 1.0, theta = 0.5 / index, emgBroadband = 0.1)
        return FocusEpoch(atMs, bands.engagementIndex, bands, ContactQuality(1, 1, 1, 1), ArtifactFlags.NONE, valid = true)
    }

    /**
     * Alternating LOW / HIGH blocks with a 10 s rest between them. Each block's epochs are drawn
     * around [lowIndex] or [highIndex].
     */
    private fun run(lowIndex: Double, highIndex: Double, blocks: Int = 6): Pair<List<CalibrationBlock>, List<FocusEpoch>> {
        val rnd = Random(7)
        val bs = (0 until blocks).map { i ->
            val c = if (i % 2 == 0) LOW else HIGH
            CalibrationBlock(c, startedAtMs = i * (blockMs + 10_000L), durationMs = blockMs, taskScore = if (c == HIGH) engaged else null)
        }
        val epochs = bs.flatMap { b ->
            val centre = if (b.condition == LOW) lowIndex else highIndex
            (0..blockMs / 1_000).map { s -> epoch(b.startedAtMs + s * 1_000, centre * (1 + (rnd.nextDouble() - 0.5) * 0.1)) }
        }
        return bs to epochs
    }

    @Test
    fun `a separable run is applied, persisted and recorded`() = runTest {
        val store = store()
        val pipeline = FocusPipeline(store = store, clock = { 999L })
        val (blocks, epochs) = run(lowIndex = 0.5, highIndex = 1.5)

        val result = pipeline.fitCalibration(blocks, epochs)

        assertEquals(1.0, result.auc, 1e-12)
        assertTrue(result.thresholds.tLowZ < result.thresholds.tHighZ)
        assertEquals(999L, result.thresholds.fittedAtMs)
        assertEquals(result.thresholds, pipeline.thresholds.value)
        assertEquals(result.thresholds, store.loadCurrent())
        assertEquals(1, store.history().size)
        assertEquals(blocks, result.blocks)
    }

    @Test
    fun `fitting keeps the user's dwell times`() = runTest {
        val pipeline = FocusPipeline(FocusThresholds.PLACEHOLDER.copy(entryDwellSec = 3, exitDwellSec = 200))
        val (blocks, epochs) = run(0.5, 1.5)

        val result = pipeline.fitCalibration(blocks, epochs)

        assertEquals(3, result.thresholds.entryDwellSec)
        assertEquals(200, result.thresholds.exitDwellSec)
    }

    @Test
    fun `a non-separable run is recorded for the evaluation but not applied`() = runTest {
        val store = store()
        val previous = FocusThresholds.PLACEHOLDER.copy(fittedAtMs = 1L)
        store.saveCurrent(previous)
        val pipeline = FocusPipeline(previous, store = store)
        val (blocks, epochs) = run(lowIndex = 1.5, highIndex = 0.5) // wrong way round

        val result = pipeline.fitCalibration(blocks, epochs)

        assertTrue(result.auc < 0.5)
        assertEquals(previous, pipeline.thresholds.value)
        assertEquals(previous, store.loadCurrent())
        assertEquals(1, store.history().size)
    }

    @Test
    fun `a non-separable first calibration leaves the pipeline uncalibrated`() = runTest {
        val pipeline = FocusPipeline(store = store())
        val (blocks, epochs) = run(1.0, 1.0)

        pipeline.fitCalibration(blocks, epochs)

        assertNull(pipeline.thresholds.value)
    }

    @Test
    fun `an empty run reports chance rather than crashing, and saves nothing`() = runTest {
        val store = store()
        val pipeline = FocusPipeline(store = store)

        val result = pipeline.fitCalibration(emptyList(), emptyList())

        assertEquals(0.5, result.auc, 0.0)
        assertNull(pipeline.thresholds.value)
        assertNull(store.loadCurrent())
        assertTrue(store.history().isEmpty())
    }

    @Test
    fun `HIGH blocks the user did not engage with are left out`() = runTest {
        val pipeline = FocusPipeline()
        val (blocks, epochs) = run(0.5, 1.5)
        val disengaged = blocks.map { if (it.condition == HIGH) it.copy(taskScore = TaskScore(2, 25)) else it }

        val result = pipeline.fitCalibration(disengaged, epochs)

        assertEquals("no HIGH data left, so nothing was fitted", 0.5, result.auc, 0.0)
        assertNull(pipeline.thresholds.value)
    }

    @Test
    fun `thresholds saved earlier are loaded at startup`() = runTest {
        val saved = FocusThresholds.PLACEHOLDER.copy(tHighZ = 0.9, fittedAtMs = 5L)
        store().saveCurrent(saved)

        val pipeline = FocusPipeline(store = store(), scope = this)
        testScheduler.advanceUntilIdle()

        assertEquals(saved, pipeline.thresholds.value)
    }

    @Test
    fun `startup load does not overwrite thresholds set in the meantime`() = runTest {
        store().saveCurrent(FocusThresholds.PLACEHOLDER.copy(fittedAtMs = 5L))
        val newer = FocusThresholds.PLACEHOLDER.copy(fittedAtMs = 6L)

        val pipeline = FocusPipeline(newer, store = store(), scope = this)
        testScheduler.advanceUntilIdle()

        assertEquals(newer, pipeline.thresholds.value)
    }

    @Test
    fun `updateThresholds persists the change`() = runTest {
        val store = store()
        val pipeline = FocusPipeline(store = store)
        val edited = FocusThresholds.PLACEHOLDER.copy(exitDwellSec = 240)

        pipeline.updateThresholds(edited)

        assertEquals(edited, store.loadCurrent())
    }

    @Test
    fun `invalid thresholds are refused and not applied`() = runTest {
        val pipeline = FocusPipeline(store = store())

        val refused = runCatching { pipeline.updateThresholds(FocusThresholds.PLACEHOLDER.copy(tHighZ = -5.0)) }

        assertTrue(refused.isFailure)
        assertFalse(pipeline.thresholds.value != null)
    }
}
