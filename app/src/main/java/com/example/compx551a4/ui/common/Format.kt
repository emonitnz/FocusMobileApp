package com.example.compx551a4.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// [D] Formatting shared across screens. Kept out of the ViewModels' UiState mapping only because
// three screens need the same rules.

/**
 * The locale as Compose observes it. Reading [Locale.getDefault] inside a composable is a snapshot
 * of process state that never invalidates, so a mid-session locale change would leave stale numbers
 * on screen until something else happened to recompose.
 */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Decimals rendered against the observable locale — "0.81" here, "0,81" in de-DE. */
@Composable
@ReadOnlyComposable
fun formatDecimal(value: Double, digits: Int = 2): String =
    String.format(currentLocale(), "%.${digits}f", value)

@Composable
@ReadOnlyComposable
fun formatPercent(fraction: Double): String =
    String.format(currentLocale(), "%d%%", (fraction * 100).toInt())

/** "2h 14m", "14m 03s", "0s". Compact enough for a stat tile. */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0s"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}

/** "1:05" — for a task countdown, where a leading "0m" would just be noise. */
fun formatCountdown(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 999L) / 1000L
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

fun formatDateTime(epochMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    return String.format(
        Locale.getDefault(),
        "%d %s, %02d:%02d",
        cal.get(Calendar.DAY_OF_MONTH),
        MONTHS[cal.get(Calendar.MONTH)],
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
    )
}

fun formatHourLabel(hour: Int): String = when {
    hour == 0 -> "12a"
    hour < 12 -> "${hour}a"
    hour == 12 -> "12p"
    else -> "${hour - 12}p"
}

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
