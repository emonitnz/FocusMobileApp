package com.example.compx551a4.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

// [D] Two charts, both single-series, both drawn on Canvas rather than pulling in a charting
// dependency for what amounts to a polyline and twenty-four rectangles.
//
// Shared rules: recessive reference lines, no legend (one series — the heading names it), labels on
// selected points only, and never a mark where there is no datum.

/** One decision, as the sparkline needs it. [accepted] false means the epoch was artifact-rejected. */
data class FocusPoint(val normalizedZ: Double, val accepted: Boolean)

/**
 * The live focus index against its two thresholds — the clearest picture of what the state machine
 * is actually doing, and the reason the hysteresis band is drawn rather than described.
 *
 * Rejected epochs break the line instead of being interpolated over. Drawing a smooth line through
 * data that was thrown away would misrepresent the one thing this chart exists to show.
 */
@Composable
fun FocusIndexChart(
    points: List<FocusPoint>,
    tHighZ: Double?,
    tLowZ: Double?,
    modifier: Modifier = Modifier,
    /**
     * How many points the x axis spans. The caller's buffer fills from empty after every screen
     * re-creation; with the axis sized to the buffer's capacity rather than its current length,
     * new points enter from the right at a steady one-per-second pace instead of the first few
     * being stretched across the whole width and then squeezed up as the buffer fills.
     */
    capacity: Int = points.size,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    bandColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    guideColor: Color = MaterialTheme.colorScheme.outline,
) {
    val description = if (points.isEmpty()) {
        "Focus index chart, no data yet"
    } else {
        "Focus index over the last ${points.size} seconds, currently " +
            String.format("%.1f", points.last().normalizedZ) + " standard deviations"
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description },
    ) {
        if (points.size < 2) return@Canvas

        // A fixed ±3 SD window, widened only if the signal actually leaves it. An auto-scaling
        // y-axis would make a calm signal look identical to a volatile one.
        val values = points.filter { it.accepted }.map { it.normalizedZ }
        val extent = max(3.0, values.maxOfOrNull { abs(it) } ?: 3.0)
        fun yOf(z: Double) = (size.height * (1.0 - (z + extent) / (2 * extent))).toFloat()
        val slots = max(capacity, points.size)
        fun xOf(i: Int) = size.width * (slots - points.size + i) / (slots - 1).toFloat()

        if (tHighZ != null && tLowZ != null) {
            // The neutral zone: inside it, neither dwell timer runs. Shaded, not outlined, so it
            // reads as background rather than as a third series.
            drawRect(
                color = bandColor,
                topLeft = Offset(0f, yOf(tHighZ)),
                size = androidx.compose.ui.geometry.Size(size.width, yOf(tLowZ) - yOf(tHighZ)),
            )
            drawGuide(yOf(tHighZ), guideColor)
            drawGuide(yOf(tLowZ), guideColor)
        }

        // One path per unbroken run of accepted epochs.
        var path: Path? = null
        points.forEachIndexed { i, point ->
            if (!point.accepted) {
                path?.let { drawPath(it, lineColor, style = Stroke(width = 2.dp.toPx())) }
                path = null
                return@forEachIndexed
            }
            val x = xOf(i)
            val y = yOf(point.normalizedZ)
            if (path == null) Path().also { it.moveTo(x, y); path = it } else path!!.lineTo(x, y)
        }
        path?.let { drawPath(it, lineColor, style = Stroke(width = 2.dp.toPx())) }

        points.lastOrNull()?.takeIf { it.accepted }?.let {
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(xOf(points.lastIndex), yOf(it.normalizedZ)))
        }
    }
}

private fun DrawScope.drawGuide(y: Float, color: Color) {
    drawLine(
        color = color.copy(alpha = 0.6f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
}

/**
 * Focused time by hour of day (DESIGN.md §4). Twenty-four ordered bins of one measure, so: bars, one
 * hue, no legend, and labels on the quarter marks only — a number above every bar would be noise.
 */
@Composable
fun TimeOfDayChart(
    focusedMsByHour: List<Long>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    emptyColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
) {
    val peak = focusedMsByHour.maxOrNull() ?: 0L
    val peakHour = focusedMsByHour.indexOfFirst { it == peak && it > 0L }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics {
                    contentDescription = if (peakHour < 0) {
                        "No focused time recorded yet"
                    } else {
                        "Focused time by hour, most focused around ${formatHourLabel(peakHour)}"
                    }
                },
        ) {
            val gap = 2.dp.toPx()
            val slot = size.width / 24f
            val barWidth = (slot - gap).coerceAtLeast(1f)
            val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())

            focusedMsByHour.forEachIndexed { hour, ms ->
                // Every hour keeps a baseline sliver, so an empty hour reads as zero rather than as
                // a gap in the axis.
                val fraction = if (peak == 0L) 0f else (ms.toFloat() / peak)
                val height = (size.height * fraction).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (ms > 0L) barColor else emptyColor,
                    topLeft = Offset(hour * slot + gap / 2f, size.height - height),
                    size = androidx.compose.ui.geometry.Size(barWidth, height),
                    cornerRadius = radius,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { hour ->
                Text(
                    formatHourLabel(hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Two class means on a shared z axis — how well calibration separated low from high demand. */
@Composable
fun SeparationBar(
    lowMeanZ: Double,
    highMeanZ: Double,
    modifier: Modifier = Modifier,
    lowColor: Color = MaterialTheme.colorScheme.outline,
    highColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                contentDescription = "Low demand mean " + String.format("%.2f", lowMeanZ) +
                    ", high demand mean " + String.format("%.2f", highMeanZ) + " standard deviations"
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val extent = max(1.5, max(abs(lowMeanZ), abs(highMeanZ)) * 1.4)
            fun xOf(z: Double) = (size.width * (z + extent) / (2 * extent)).toFloat()
            val y = size.height / 2f

            drawLine(
                color = lowColor.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(lowColor, radius = 7.dp.toPx(), center = Offset(xOf(lowMeanZ), y))
            drawCircle(highColor, radius = 7.dp.toPx(), center = Offset(xOf(highMeanZ), y))
        }
    }
}
