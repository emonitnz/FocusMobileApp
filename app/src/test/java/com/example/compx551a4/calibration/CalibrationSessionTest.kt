package com.example.compx551a4.calibration

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.CalibrationCondition.HIGH
import com.example.compx551a4.core.CalibrationCondition.LOW
import com.example.compx551a4.core.CalibrationCondition.MEDIUM
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.TaskScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSessionTest {

    private val session = CalibrationSession(settleMs = 5_000L, minAccuracy = 0.5)

    private val blockMs = 60_000L

    /** Blocks laid out back to back with a 10 s rest, starting at 0. */
    private fun blocks(vararg specs: Pair<CalibrationCondition, TaskScore?>) = specs.mapIndexed { i, (c, s) ->
        CalibrationBlock(c, startedAtMs = i * (blockMs + 10_000L), durationMs = blockMs, taskScore = s)
    }

    /** An epoch every second across [block], with Pope index [index]. */
    private fun epochsFor(block: CalibrationBlock, index: Double) =
        (0..block.durationMs / 1_000).map { epoch(block.startedAtMs + it * 1_000, index) }

    private fun epoch(atMs: Long, index: Double, valid: Boolean = true): FocusEpoch {
        // Index moved through alpha + theta with beta fixed, so the muscle check never fires.
        val bands = BandPowers(alphaTemporal = 0.5 / index, beta1320 = 1.0, theta = 0.5 / index, emgBroadband = 0.1)
        return FocusEpoch(
            timestampMs = atMs,
            focusIndex = bands.engagementIndex,
            bands = bands,
            contact = ContactQuality(1, 1, 1, 1),
            artifacts = if (valid) ArtifactFlags.NONE else ArtifactFlags(blink = true),
            valid = valid,
        )
    }

    private val engaged = TaskScore(correct = 20, total = 25)

    @Test
    fun `labels each sample with its block's condition and position`() {
        val bs = blocks(LOW to null, HIGH to engaged, MEDIUM to TaskScore(1, 1))
        val epochs = epochsFor(bs[0], 0.5) + epochsFor(bs[1], 2.0) + epochsFor(bs[2], 1.0)

        val p = session.prepare(bs, epochs)

        assertEquals(setOf(0, 1, 2), p.samples.map { it.block }.toSet())
        assertTrue(p.samples.filter { it.block == 0 }.all { it.condition == LOW && it.index == 0.5 })
        assertTrue(p.samples.filter { it.block == 1 }.all { it.condition == HIGH && it.index == 2.0 })
        assertTrue(p.dropped.isEmpty())
    }

    @Test
    fun `drops the settling period at the start of each block`() {
        val bs = blocks(LOW to null)

        val p = session.prepare(bs, epochsFor(bs[0], 1.0))

        // Epochs at 0..60 s; 0..4 s are settling, 5..60 s are kept.
        assertEquals(56, p.samples.size)
    }

    @Test
    fun `epochs outside every block are ignored`() {
        val bs = blocks(LOW to null, LOW to null)
        val inRest = (61..69).map { epoch(it * 1_000L, 9.0) }

        val p = session.prepare(bs, epochsFor(bs[0], 1.0) + inRest + epochsFor(bs[1], 1.0))

        assertTrue(p.samples.none { it.index == 9.0 })
    }

    @Test
    fun `drops task blocks where the user clearly did not engage`() {
        val bs = blocks(
            HIGH to null,
            HIGH to TaskScore(correct = 0, total = 0),
            HIGH to TaskScore(correct = 10, total = 25),
            MEDIUM to TaskScore(correct = 0, total = 1),
            HIGH to engaged,
        )
        val epochs = bs.flatMap { epochsFor(it, 1.0) }

        val p = session.prepare(bs, epochs)

        assertEquals(
            listOf(DropReason.NO_TASK_SCORE, DropReason.NO_ATTEMPTS, DropReason.LOW_ACCURACY, DropReason.LOW_ACCURACY),
            p.dropped.map { it.reason },
        )
        assertEquals(listOf(bs[4]), p.keptBlocks)
        assertEquals(setOf(4), p.samples.map { it.block }.toSet())
    }

    @Test
    fun `accuracy exactly at the minimum is kept`() {
        val bs = blocks(HIGH to TaskScore(correct = 10, total = 20))

        assertTrue(session.prepare(bs, epochsFor(bs[0], 1.0)).dropped.isEmpty())
    }

    @Test
    fun `LOW blocks are never dropped for lacking a task score`() {
        val bs = blocks(LOW to null)

        assertEquals(bs, session.prepare(bs, epochsFor(bs[0], 1.0)).keptBlocks)
    }

    @Test
    fun `artifact epochs and their holdoff are excluded`() {
        val bs = blocks(LOW to null)
        val epochs = epochsFor(bs[0], 1.0).map { if (it.timestampMs == 30_000L) it.copy(valid = false) else it }

        val p = session.prepare(bs, epochs)

        // 56 kept normally, minus the artifact at 30 s and its 2 s holdoff.
        assertEquals(53, p.samples.size)
    }

    @Test
    fun `epoch order in the input does not matter`() {
        val bs = blocks(LOW to null, HIGH to engaged)
        val epochs = epochsFor(bs[0], 0.5) + epochsFor(bs[1], 2.0)

        assertEquals(
            session.prepare(bs, epochs).samples.toSet(),
            session.prepare(bs, epochs.shuffled(kotlin.random.Random(3))).samples.toSet(),
        )
    }

    @Test
    fun `feeds the fitter end to end`() {
        val bs = blocks(LOW to null, HIGH to engaged, LOW to null, HIGH to engaged)
        val epochs = epochsFor(bs[0], 0.5) + epochsFor(bs[1], 2.0) + epochsFor(bs[2], 0.6) + epochsFor(bs[3], 1.8)

        val fit = ThresholdFitter().fit(session.prepare(bs, epochs).samples, 10, 60, 0L) as FitOutcome.Fitted

        assertEquals(1.0, fit.auc, 1e-12)
        assertTrue(fit.separable)
    }
}
