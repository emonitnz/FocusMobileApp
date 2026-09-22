package com.example.compx551a4.ui.calibration.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.compx551a4.ui.common.formatCountdown

// [D] The frame every calibration task sits in.
//
// DESIGN.md §2 requires eyes open with MATCHED VISUAL INPUT across all three conditions. That is
// not a copy detail — a low-demand block that looks different from a high-demand block means the
// comparison measures screen brightness as much as cognition. So every task gets: the same fixed
// dark background (see CalibrationTaskTheme), the same instruction line at the top, the same
// progress bar at the bottom, and its content in the same centred region between them.

/** The one place task layout is defined. Tasks supply only their centre content. */
@Composable
fun TaskScaffold(
    instruction: String,
    progress: Float,
    remainingMs: Long,
    modifier: Modifier = Modifier,
    /** "Block 3 of 9". Null during the baseline, which is not one of the nine. */
    blockLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            instruction,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        )

        // weight(1f) on every task's content region: identical geometry in all three conditions.
        Box(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                blockLabel.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatCountdown(remainingMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
