package com.example.compx551a4.ui

import com.example.compx551a4.ui.common.formatCountdown
import com.example.compx551a4.ui.common.formatDuration
import com.example.compx551a4.ui.common.formatHourLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `durations drop seconds once hours are involved`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("0s", formatDuration(-5))
        assertEquals("42s", formatDuration(42_000))
        assertEquals("2m 05s", formatDuration(125_000))
        assertEquals("2h 5m", formatDuration(7_500_000))
    }

    /** A countdown rounds up: showing 0:00 while a second of the block remains would be a lie. */
    @Test
    fun `countdown rounds up to the next whole second`() {
        assertEquals("1:00", formatCountdown(60_000))
        assertEquals("0:01", formatCountdown(1))
        assertEquals("0:00", formatCountdown(0))
        assertEquals("0:00", formatCountdown(-100))
    }

    @Test
    fun `hour labels use twelve hour clock with midnight and noon`() {
        assertEquals("12a", formatHourLabel(0))
        assertEquals("9a", formatHourLabel(9))
        assertEquals("12p", formatHourLabel(12))
        assertEquals("11p", formatHourLabel(23))
    }
}
