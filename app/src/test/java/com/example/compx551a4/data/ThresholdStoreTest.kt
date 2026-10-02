package com.example.compx551a4.data

import com.example.compx551a4.core.FocusThresholds
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ThresholdStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = File(tmp.root, "thresholds")

    private fun store() = ThresholdStore(dir, UnconfinedTestDispatcher())

    private val fitted = FocusThresholds(
        tHighZ = 0.734,
        tLowZ = -0.1234567890123,
        entryDwellSec = 8,
        exitDwellSec = 45,
        fittedAtMs = 1_759_000_000_000L,
        auc = 0.81,
    )

    private fun record(atMs: Long, auc: Double = 0.8) = CalibrationRecord(
        thresholds = fitted.copy(fittedAtMs = atMs, auc = auc),
        auc = auc,
        lowMeanZ = -0.6,
        highMeanZ = 0.9,
        blockCount = 9,
        indexMean = 0.42,
        indexSd = 0.07,
    )

    @Test
    fun `nothing stored means not calibrated`() = runTest {
        assertNull(store().loadCurrent())
        assertTrue(store().history().isEmpty())
    }

    @Test
    fun `current thresholds round-trip exactly`() = runTest {
        store().saveCurrent(fitted)

        assertEquals(fitted, store().loadCurrent())
    }

    @Test
    fun `saving again replaces the current thresholds`() = runTest {
        val s = store()
        s.saveCurrent(fitted)
        s.saveCurrent(fitted.copy(exitDwellSec = 120))

        assertEquals(120, store().loadCurrent()!!.exitDwellSec)
        assertFalse("no temp file left behind", File(dir, "current.txt.tmp").exists())
    }

    @Test
    fun `recording a calibration appends history and updates the current thresholds`() = runTest {
        val s = store()
        s.recordCalibration(record(1_000L, auc = 0.7))
        s.recordCalibration(record(2_000L, auc = 0.9))

        val reopened = store()
        assertEquals(listOf(record(1_000L, auc = 0.7), record(2_000L, auc = 0.9)), reopened.history())
        assertEquals(record(2_000L, auc = 0.9), reopened.latestCalibration())
        assertEquals(record(2_000L, auc = 0.9).thresholds, reopened.loadCurrent())
    }

    @Test
    fun `changing the exit timeout does not rewrite history`() = runTest {
        val s = store()
        s.recordCalibration(record(1_000L))
        s.saveCurrent(record(1_000L).thresholds.copy(exitDwellSec = 300))

        assertEquals(45, s.history().single().thresholds.exitDwellSec)
        assertEquals(300, s.loadCurrent()!!.exitDwellSec)
    }

    @Test
    fun `optional index stats may be absent`() = runTest {
        val r = record(1_000L).copy(indexMean = null, indexSd = null)
        store().recordCalibration(r)

        assertEquals(r, store().history().single())
    }

    @Test
    fun `a corrupt history line is skipped, not fatal`() = runTest {
        val s = store()
        s.recordCalibration(record(1_000L))
        File(dir, "history.txt").appendText("garbage\ntHighZ=not-a-number\n")
        s.recordCalibration(record(2_000L))

        assertEquals(listOf(1_000L, 2_000L), store().history().map { it.fittedAtMs })
    }

    @Test
    fun `a corrupt current file reads as not calibrated`() = runTest {
        dir.mkdirs()
        File(dir, "current.txt").writeText("tHighZ=0.5;oops\n")

        assertNull(store().loadCurrent())
    }

    @Test
    fun `unknown keys are ignored so the format can grow`() = runTest {
        store().saveCurrent(fitted)
        val f = File(dir, "current.txt")
        f.writeText(f.readText().trim() + ";someFutureField=1\n")

        assertEquals(fitted, store().loadCurrent())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects T_low above T_high`() = runTest {
        store().saveCurrent(fitted.copy(tHighZ = -1.0, tLowZ = 1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-finite thresholds`() = runTest {
        store().recordCalibration(record(1_000L).copy(thresholds = fitted.copy(tHighZ = Double.NaN)))
    }

    @Test
    fun `clear forgets everything`() = runTest {
        val s = store()
        s.recordCalibration(record(1_000L))
        s.clear()

        assertNull(s.loadCurrent())
        assertTrue(s.history().isEmpty())
    }
}
