package com.example.compx551a4.calibration

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.FocusEpoch
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationSessionSettleTest {

    private fun block(durationMs: Long) = CalibrationBlock(CalibrationCondition.LOW, startedAtMs = 0L, durationMs = durationMs)

    private fun epoch(at: Long) = FocusEpoch(
        timestampMs = at,
        focusIndex = 1.0,
        bands = BandPowers(alphaTemporal = 1.0, beta1320 = 2.0, theta = 1.0, emgBroadband = 0.1),
        contact = ContactQuality(1, 1, 1, 1),
        artifacts = ArtifactFlags.NONE,
        valid = true,
    )

    @Test
    fun `a full-length block settles for the full five seconds`() {
        assertEquals(5_000L, CalibrationSession().settleFor(block(60_000L)))
    }

    @Test
    fun `a longer block is capped at settleMs`() {
        assertEquals(5_000L, CalibrationSession().settleFor(block(120_000L)))
    }

    @Test
    fun `a fast-mode block settles for a proportionate slice`() {
        assertEquals(500L, CalibrationSession().settleFor(block(6_000L)))
    }

    @Test
    fun `a fast-mode block keeps every epoch after its settle`() {
        val epochs = (0L..6_000L step 1_000L).map(::epoch)

        val prepared = CalibrationSession().prepare(listOf(block(6_000L)), epochs)

        // 0 s is inside the 0.5 s settle; 1-6 s survive. A flat 5 s settle would keep only 5-6 s.
        assertEquals(6, prepared.samples.size)
    }
}
