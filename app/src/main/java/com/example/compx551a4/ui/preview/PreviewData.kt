package com.example.compx551a4.ui.preview

import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.SessionStats
import com.example.compx551a4.core.SessionSummary
import com.example.compx551a4.ui.common.FocusPoint
import com.example.compx551a4.ui.home.HomeUiState
import com.example.compx551a4.ui.home.StartBlocker
import kotlin.math.sin
import kotlin.random.Random

// [D] Fixed sample values for @Preview. Deterministic — a preview that reshuffles on every
// recomposition is useless for spotting a layout regression.

object PreviewData {

    val samplePoints: List<FocusPoint> = run {
        val random = Random(11)
        List(120) { i ->
            FocusPoint(
                normalizedZ = sin(i / 14.0) * 1.3 + random.nextDouble(-0.3, 0.3),
                // A blink every twenty seconds or so, to keep the gaps visible in the preview.
                accepted = i % 23 != 0,
            )
        }
    }

    private val deferredSample = listOf(
        DeferredNotification("1", "com.Slack", "Slack", "3 new messages", 0L),
        DeferredNotification("2", "com.Slack", "Slack", "Standup in 10", 0L),
        DeferredNotification("3", "com.google.android.gm", "Gmail", "Re: marking guide", 0L),
    )

    val homeFocused = HomeUiState(
        serviceRunning = true,
        blocker = null,
        focusState = FocusState.FOCUSED,
        stateForMs = 14 * 60_000L + 12_000L,
        device = DeviceStatus(
            state = DeviceState.CONNECTED,
            deviceName = "Muse-4F2A",
            batteryPct = 84,
            contact = ContactQuality(1, 1, 2, 1),
        ),
        points = samplePoints,
        tHighZ = 0.55,
        tLowZ = -0.2,
        deferred = deferredSample,
    )

    val homeUncalibrated = HomeUiState(
        serviceRunning = false,
        blocker = StartBlocker.NOT_CALIBRATED,
        focusState = FocusState.UNKNOWN,
        device = DeviceStatus(
            state = DeviceState.POOR_CONTACT,
            deviceName = "Muse-4F2A",
            batteryPct = 61,
            contact = ContactQuality(1, 3, 4, 2),
        ),
    )

    val sampleStats: SessionStats = SessionStats(
        sessions = listOf(
            session(dayOffset = 3, startHour = 9, focusedMinutes = 48, streakMinutes = 22, deferred = 14),
            session(dayOffset = 2, startHour = 14, focusedMinutes = 71, streakMinutes = 31, deferred = 26),
            session(dayOffset = 1, startHour = 10, focusedMinutes = 26, streakMinutes = 12, deferred = 6),
            session(dayOffset = 0, startHour = 20, focusedMinutes = 55, streakMinutes = 40, deferred = 19),
        ),
    )

    private fun session(
        dayOffset: Int,
        startHour: Int,
        focusedMinutes: Int,
        streakMinutes: Int,
        deferred: Int,
    ): SessionSummary {
        val dayMs = 86_400_000L
        val start = 1_758_000_000_000L - dayOffset * dayMs + startHour * 3_600_000L
        val hours = LongArray(24)
        hours[startHour] = focusedMinutes * 60_000L
        return SessionSummary(
            startedAtMs = start,
            endedAtMs = start + focusedMinutes * 90_000L,
            focusedMs = focusedMinutes * 60_000L,
            longestStreakMs = streakMinutes * 60_000L,
            deferredCount = deferred,
            deliveredCount = deferred,
            flushCount = 3,
            focusedMsByHour = hours.toList(),
        )
    }
}
