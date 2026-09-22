package com.example.compx551a4.data

import android.content.Context
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.SessionStats
import com.example.compx551a4.core.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

// [D] Session history. Implements SessionRecorder.
//
// Storage is an append-only JSONL file: one completed session per line, written once at session end.
// Decisions arrive at ~1 Hz from the service's coroutine scope, so they are accumulated in memory
// and never touch the disk individually — per-decision file I/O at 1 Hz for hours would be the
// wrong trade for data we only ever read in aggregate.
//
// org.json is used rather than kotlinx.serialization to avoid adding a compiler plugin for the two
// small records this file persists.

class SessionStore(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionRecorder {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val writeMutex = Mutex()

    private val _stats = MutableStateFlow(SessionStats())
    override val stats: Flow<SessionStats> = _stats.asStateFlow()

    /** Null between sessions. Guarded by [writeMutex] along with every accumulator below. */
    private var current: Accumulator? = null

    init {
        scope.launch { _stats.value = SessionStats(readAll()) }
    }

    override suspend fun onSessionStart(atMs: Long) = writeMutex.withLock {
        current = Accumulator(startedAtMs = atMs)
    }

    /**
     * Focused time is measured between transitions rather than by counting FOCUSED decisions, so a
     * dropped or rejected epoch does not silently shorten the total.
     */
    override suspend fun onDecision(decision: FocusDecision) = writeMutex.withLock {
        val acc = current ?: return@withLock
        val wasFocused = acc.focusedSinceMs != null
        val isFocused = decision.state == FocusState.FOCUSED

        when {
            !wasFocused && isFocused -> acc.focusedSinceMs = decision.timestampMs
            wasFocused && !isFocused -> acc.closeStreak(decision.timestampMs)
        }
        acc.lastSeenMs = decision.timestampMs
    }

    override suspend fun onDeferred(notification: DeferredNotification) = writeMutex.withLock {
        current?.let { it.deferredCount++ }
        Unit
    }

    override suspend fun onFlush(count: Int, reason: FlushReason, atMs: Long) = writeMutex.withLock {
        current?.let {
            it.flushCount++
            it.deliveredCount += count
        }
        Unit
    }

    override suspend fun onSessionEnd(atMs: Long) {
        val summary = writeMutex.withLock {
            val acc = current ?: return
            acc.closeStreak(atMs)
            current = null
            acc.toSummary(endedAtMs = atMs)
        }
        // A session with no focused time and nothing deferred tells the stats screen nothing.
        if (summary.focusedMs == 0L && summary.deferredCount == 0) return
        append(summary)
        _stats.value = SessionStats(_stats.value.sessions + summary)
    }

    /** Developer option — clears the history behind the stats screen. */
    suspend fun clear() {
        withContext(Dispatchers.IO) { file.delete() }
        _stats.value = SessionStats()
    }

    // -----------------------------------------------------------------------
    // Accumulation
    // -----------------------------------------------------------------------

    private class Accumulator(val startedAtMs: Long) {
        var focusedSinceMs: Long? = null
        var lastSeenMs: Long = startedAtMs
        var focusedMs: Long = 0L
        var longestStreakMs: Long = 0L
        var deferredCount: Int = 0
        var deliveredCount: Int = 0
        var flushCount: Int = 0
        val byHour = LongArray(24)

        fun closeStreak(endMs: Long) {
            val start = focusedSinceMs ?: return
            focusedSinceMs = null
            val streak = (endMs - start).coerceAtLeast(0L)
            focusedMs += streak
            if (streak > longestStreakMs) longestStreakMs = streak
            splitAcrossHours(start, endMs)
        }

        /** A streak can cross midnight or an hour boundary, so it is split rather than bucketed once. */
        private fun splitAcrossHours(startMs: Long, endMs: Long) {
            val cal = Calendar.getInstance()
            var cursor = startMs
            while (cursor < endMs) {
                cal.timeInMillis = cursor
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val nextHour = cal.timeInMillis + HOUR_MS
                val sliceEnd = minOf(nextHour, endMs)
                byHour[hour] += sliceEnd - cursor
                cursor = sliceEnd
            }
        }

        fun toSummary(endedAtMs: Long) = SessionSummary(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            focusedMs = focusedMs,
            longestStreakMs = longestStreakMs,
            deferredCount = deferredCount,
            deliveredCount = deliveredCount,
            flushCount = flushCount,
            focusedMsByHour = byHour.toList(),
        )
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private suspend fun append(summary: SessionSummary) = withContext(Dispatchers.IO) {
        runCatching { file.appendText(summary.toJson().toString() + "\n") }
    }

    private suspend fun readAll(): List<SessionSummary> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            // A truncated final line after a crash should cost one session, not the whole history.
            .mapNotNull { line -> runCatching { JSONObject(line).toSummary() }.getOrNull() }
    }

    private companion object {
        const val FILE_NAME = "sessions.jsonl"
        const val HOUR_MS = 60L * 60L * 1000L
    }
}

private fun SessionSummary.toJson() = JSONObject().apply {
    put("startedAtMs", startedAtMs)
    put("endedAtMs", endedAtMs)
    put("focusedMs", focusedMs)
    put("longestStreakMs", longestStreakMs)
    put("deferredCount", deferredCount)
    put("deliveredCount", deliveredCount)
    put("flushCount", flushCount)
    put("focusedMsByHour", JSONArray(focusedMsByHour))
}

private fun JSONObject.toSummary(): SessionSummary {
    val hours = optJSONArray("focusedMsByHour")
    return SessionSummary(
        startedAtMs = getLong("startedAtMs"),
        endedAtMs = getLong("endedAtMs"),
        focusedMs = getLong("focusedMs"),
        longestStreakMs = getLong("longestStreakMs"),
        deferredCount = getInt("deferredCount"),
        deliveredCount = optInt("deliveredCount"),
        flushCount = optInt("flushCount"),
        focusedMsByHour = List(24) { hours?.optLong(it) ?: 0L },
    )
}
