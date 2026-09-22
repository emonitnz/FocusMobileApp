package com.example.compx551a4.ui.calibration.tasks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 */
@Composable
fun FixationTask(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "fixation")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )

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
