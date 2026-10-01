package com.example.compx551a4.data

import android.content.Context
import com.example.compx551a4.core.CalibrationResult
import com.example.compx551a4.core.FocusThresholds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// [B] Persists thresholds as ratios/z-scores (never raw uV^2) + calibration history.
//
// Two files under one directory:
//   current.txt  — the thresholds in use, one record. Rewritten atomically (temp file + rename),
//                  because the user can change the exit timeout at any time.
//   history.txt  — append-only, one calibration per line, oldest first.
//
// Records are `key=value` pairs separated by `;`. org.json would match SessionStore, but it is a
// stub in local JVM tests; this format keeps the store testable without Robolectric, and unknown
// keys are ignored so fields can be added later without breaking old files.

/**
 * One completed calibration, kept so drift can be detected later (DESIGN.md §2).
 *
 * Everything here is a z-score, a ratio or a count — never a raw band power. [indexMean] and
 * [indexSd] describe the Pope index (itself a ratio) over the calibration run, which is what the
 * running distribution is compared against; they are null if the fitter did not report them.
 */
data class CalibrationRecord(
    val thresholds: FocusThresholds,
    val auc: Double,
    val lowMeanZ: Double,
    val highMeanZ: Double,
    val blockCount: Int,
    val indexMean: Double? = null,
    val indexSd: Double? = null,
) {
    val fittedAtMs: Long get() = thresholds.fittedAtMs

    companion object {
        fun from(result: CalibrationResult, indexMean: Double? = null, indexSd: Double? = null) =
            CalibrationRecord(
                thresholds = result.thresholds,
                auc = result.auc,
                lowMeanZ = result.lowMeanZ,
                highMeanZ = result.highMeanZ,
                blockCount = result.blocks.size,
                indexMean = indexMean,
                indexSd = indexSd,
            )
    }
}

class ThresholdStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIR_NAME))

    private val currentFile = File(dir, CURRENT_FILE)
    private val historyFile = File(dir, HISTORY_FILE)
    private val mutex = Mutex()

    /** The thresholds in use, or null if the user has never calibrated (or the file is unreadable). */
    suspend fun loadCurrent(): FocusThresholds? = mutex.withLock {
        withContext(io) {
            if (!currentFile.exists()) return@withContext null
            currentFile.readText().lineSequence().firstOrNull { it.isNotBlank() }
                ?.let { runCatching { decodeThresholds(parse(it)) }.getOrNull() }
        }
    }

    /** Replaces the thresholds in use — e.g. after the user changes the exit timeout. */
    suspend fun saveCurrent(thresholds: FocusThresholds) {
        validate(thresholds)
        mutex.withLock { withContext(io) { writeCurrent(thresholds) } }
    }

    /**
     * Appends [record] to the history and, if [makeCurrent], makes its thresholds the ones in use.
     * A run too poorly separated to act on is still worth keeping for the evaluation.
     */
    suspend fun recordCalibration(record: CalibrationRecord, makeCurrent: Boolean = true) {
        validate(record.thresholds)
        mutex.withLock {
            withContext(io) {
                dir.mkdirs()
                historyFile.appendText(format(encodeRecord(record)) + "\n")
                if (makeCurrent) writeCurrent(record.thresholds)
            }
        }
    }

    /** Every calibration, oldest first. Unreadable lines are skipped rather than failing the lot. */
    suspend fun history(): List<CalibrationRecord> = mutex.withLock {
        withContext(io) {
            if (!historyFile.exists()) return@withContext emptyList()
            historyFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { decodeRecord(parse(it)) }.getOrNull() }
        }
    }

    /** Most recent calibration, or null if there is none. */
    suspend fun latestCalibration(): CalibrationRecord? = history().lastOrNull()

    /** Developer option — forget the thresholds and the history. */
    suspend fun clear() = mutex.withLock {
        withContext(io) {
            currentFile.delete()
            historyFile.delete()
        }
        Unit
    }

    private fun writeCurrent(thresholds: FocusThresholds) {
        dir.mkdirs()
        val tmp = File(dir, "$CURRENT_FILE.tmp")
        tmp.writeText(format(encodeThresholds(thresholds)) + "\n")
        if (!tmp.renameTo(currentFile)) {
            // renameTo does not overwrite on every platform; fall back to delete-then-rename.
            currentFile.delete()
            check(tmp.renameTo(currentFile)) { "could not replace $currentFile" }
        }
    }

    private fun validate(t: FocusThresholds) {
        require(t.tHighZ.isFinite() && t.tLowZ.isFinite()) { "thresholds must be finite: $t" }
        require(t.tLowZ <= t.tHighZ) { "T_low must not exceed T_high: $t" }
        require(t.entryDwellSec >= 0 && t.exitDwellSec >= 0) { "dwell times must be non-negative: $t" }
    }

    // -----------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------

    private fun encodeThresholds(t: FocusThresholds): Map<String, String> = linkedMapOf(
        "tHighZ" to t.tHighZ.toString(),
        "tLowZ" to t.tLowZ.toString(),
        "entryDwellSec" to t.entryDwellSec.toString(),
        "exitDwellSec" to t.exitDwellSec.toString(),
        "fittedAtMs" to t.fittedAtMs.toString(),
        "auc" to t.auc.toString(),
    )

    private fun decodeThresholds(m: Map<String, String>) = FocusThresholds(
        tHighZ = m.getValue("tHighZ").toDouble(),
        tLowZ = m.getValue("tLowZ").toDouble(),
        entryDwellSec = m.getValue("entryDwellSec").toInt(),
        exitDwellSec = m.getValue("exitDwellSec").toInt(),
        fittedAtMs = m.getValue("fittedAtMs").toLong(),
        auc = m.getValue("auc").toDouble(),
    )

    private fun encodeRecord(r: CalibrationRecord): Map<String, String> =
        encodeThresholds(r.thresholds) + linkedMapOf(
            "calAuc" to r.auc.toString(),
            "lowMeanZ" to r.lowMeanZ.toString(),
            "highMeanZ" to r.highMeanZ.toString(),
            "blockCount" to r.blockCount.toString(),
        ) + listOfNotNull(
            r.indexMean?.let { "indexMean" to it.toString() },
            r.indexSd?.let { "indexSd" to it.toString() },
        )

    private fun decodeRecord(m: Map<String, String>) = CalibrationRecord(
        thresholds = decodeThresholds(m),
        auc = m.getValue("calAuc").toDouble(),
        lowMeanZ = m.getValue("lowMeanZ").toDouble(),
        highMeanZ = m.getValue("highMeanZ").toDouble(),
        blockCount = m.getValue("blockCount").toInt(),
        indexMean = m["indexMean"]?.toDouble(),
        indexSd = m["indexSd"]?.toDouble(),
    )

    private fun format(fields: Map<String, String>): String =
        fields.entries.joinToString(";") { (k, v) -> "$k=$v" }

    private fun parse(line: String): Map<String, String> = line.split(';').associate { field ->
        val eq = field.indexOf('=')
        require(eq > 0) { "malformed field: $field" }
        field.substring(0, eq).trim() to field.substring(eq + 1).trim()
    }

    private companion object {
        const val DIR_NAME = "thresholds"
        const val CURRENT_FILE = "current.txt"
        const val HISTORY_FILE = "history.txt"
    }
}