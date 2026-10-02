package com.example.compx551a4.signal

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.FocusEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtifactRejectorTest {

    private fun epoch(
        atSec: Int,
        beta: Double = 1.0,
        valid: Boolean = true,
        contact: ContactQuality = ContactQuality(1, 1, 1, 1),
        alpha: Double = 1.0,
    ): FocusEpoch {
        val bands = BandPowers(alphaTemporal = alpha, beta1320 = beta, theta = 1.0, emgBroadband = 0.1)
        return FocusEpoch(
            timestampMs = atSec * 1_000L,
            focusIndex = bands.engagementIndex,
            bands = bands,
            contact = contact,
            artifacts = if (valid) ArtifactFlags.NONE else ArtifactFlags(blink = true),
            valid = valid,
        )
    }

    /** Fills the median window with clean beta = 1.0 at 1 Hz; returns the next free second. */
    private fun ArtifactRejector.warm(samples: Int = 20): Int {
        repeat(samples) { assertNull(check(epoch(it))) }
        return samples
    }

    @Test
    fun `clean epochs are accepted`() {
        val rejector = ArtifactRejector()
        rejector.warm()
    }

    @Test
    fun `rejects epochs the source marked invalid`() {
        assertEquals(RejectReason.SOURCE_INVALID, ArtifactRejector().check(epoch(0, valid = false)))
    }

    @Test
    fun `rejects worse than good contact on any single electrode`() {
        val rejector = ArtifactRejector()
        listOf(
            ContactQuality(2, 1, 1, 1),
            ContactQuality(1, 2, 1, 1),
            ContactQuality(1, 1, 4, 1),
            ContactQuality(1, 1, 1, 3),
        ).forEachIndexed { i, contact ->
            assertEquals(RejectReason.POOR_CONTACT, rejector.check(epoch(i * 10, contact = contact)))
        }
    }

    @Test
    fun `rejects non-finite values`() {
        val rejector = ArtifactRejector()
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEachIndexed { i, bad ->
            assertEquals(RejectReason.NON_FINITE, rejector.check(epoch(i * 10, beta = bad)))
            assertEquals(RejectReason.NON_FINITE, rejector.check(epoch(i * 10 + 5, alpha = bad)))
        }
    }

    @Test
    fun `rejects beta above 1_3x the recent median and accepts it at or below`() {
        val rejector = ArtifactRejector()
        val t = rejector.warm()

        assertNull(rejector.check(epoch(t, beta = 1.3)))
        assertEquals(RejectReason.MUSCLE, rejector.check(epoch(t + 10, beta = 1.31)))
    }

    @Test
    fun `the muscle threshold is relative to this user's own level`() {
        val rejector = ArtifactRejector()
        repeat(20) { assertNull(rejector.check(epoch(it, beta = 50.0))) }

        assertNull(rejector.check(epoch(20, beta = 60.0)))
        assertEquals(RejectReason.MUSCLE, rejector.check(epoch(30, beta = 70.0)))
    }

    @Test
    fun `the muscle check waits for enough history`() {
        val rejector = ArtifactRejector(minMedianSamples = 10)
        repeat(9) { assertNull(rejector.check(epoch(it))) }

        assertNull("only 9 samples, no median yet", rejector.check(epoch(9, beta = 5.0)))
    }

    @Test
    fun `rejects the next 2 s after an artifact, then accepts again`() {
        val rejector = ArtifactRejector()
        val t = rejector.warm()

        assertEquals(RejectReason.SOURCE_INVALID, rejector.check(epoch(t, valid = false)))
        assertEquals(RejectReason.HOLDOFF, rejector.check(epoch(t + 1)))
        assertEquals(RejectReason.HOLDOFF, rejector.check(epoch(t + 2)))
        assertNull(rejector.check(epoch(t + 3)))
    }

    @Test
    fun `holdoff follows every kind of rejection`() {
        val rejector = ArtifactRejector()
        var t = rejector.warm()
        listOf(
            epoch(t, valid = false),
            epoch(t + 10, contact = ContactQuality(2, 1, 1, 1)),
            epoch(t + 20, beta = Double.NaN),
            epoch(t + 30, beta = 10.0),
        ).forEach { bad ->
            val at = (bad.timestampMs / 1_000).toInt()
            assertEquals(true, rejector.check(bad) != null)
            assertEquals(RejectReason.HOLDOFF, rejector.check(epoch(at + 1)))
        }
    }

    @Test
    fun `a new artifact restarts the holdoff but holdoff rejections do not extend it`() {
        val rejector = ArtifactRejector()
        val t = rejector.warm()

        rejector.check(epoch(t, valid = false))
        assertEquals(RejectReason.SOURCE_INVALID, rejector.check(epoch(t + 1, valid = false)))
        assertEquals(RejectReason.HOLDOFF, rejector.check(epoch(t + 2)))
        assertEquals(RejectReason.HOLDOFF, rejector.check(epoch(t + 3)))
        assertNull(rejector.check(epoch(t + 4)))
    }

    @Test
    fun `a sustained rise in baseline beta is eventually absorbed rather than locking the gate`() {
        val rejector = ArtifactRejector(medianWindow = 60)
        val t = rejector.warm(60)

        val verdicts = (0 until 60).map { rejector.check(epoch(t + it, beta = 2.0)) }

        assertEquals(RejectReason.MUSCLE, verdicts.first())
        assertNull(verdicts.last())
    }

    @Test
    fun `reset clears the holdoff and the median history`() {
        val rejector = ArtifactRejector()
        val t = rejector.warm()
        rejector.check(epoch(t, valid = false))

        rejector.reset()

        assertNull("holdoff cleared", rejector.check(epoch(t + 1)))
        assertNull("no median, so no muscle check", rejector.check(epoch(t + 10, beta = 10.0)))
    }
}
