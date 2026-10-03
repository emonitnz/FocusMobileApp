package com.example.compx551a4.ui.calibration.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// [D] LOW condition. No task, no score.

/**
 * A slowly drifting dot rather than a static cross. Both are in DESIGN.md §2; the moving dot is the
 * better choice because it gives the eyes something to track, which discourages the drifting gaze
 * and the slow blinks that a static fixation point tends to produce — and blinks are the artifact
 * this whole pipeline is most sensitive to.
 *
 * The drift is slow (one lap per 20 s) and small: enough to hold the eyes, not enough to be a task.
 *
 * Driven from the frame clock rather than an animator on purpose: animators honour the system's
 * "Animator duration scale", and with animations switched off in Developer options the dot would
 * sit still. It is part of the protocol, not decoration, so it must move regardless.
 */
@Composable
fun FixationTask(modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNs = withFrameNanos { it }
        while (true) {
            withFrameNanos { nowNs ->
                val elapsedMs = (nowNs - startNs) / 1_000_000.0
                angle = ((elapsedMs % LAP_MS) / LAP_MS * 2 * Math.PI).toFloat()
            }
        }
    }

    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "A dot drifting slowly in a circle" },
    ) {
        val radius = size.minDimension * 0.18f
        val centre = Offset(
            x = size.width / 2f + cos(angle) * radius,
            y = size.height / 2f + sin(angle) * radius,
        )
        drawCircle(color = color, radius = 7.dp.toPx(), center = centre)
    }
}

/** One lap of the circle. */
private const val LAP_MS = 20_000.0
