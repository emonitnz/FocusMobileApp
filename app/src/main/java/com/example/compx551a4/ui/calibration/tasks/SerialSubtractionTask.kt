package com.example.compx551a4.ui.calibration.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.compx551a4.R
import com.example.compx551a4.core.TaskScore
import kotlin.random.Random

// [D] HIGH condition, option two: serial subtraction by 7.
//
// Offered alongside 2-back because the two load attention differently, and because some people
// find 2-back so unpleasant that they disengage — which produces a low-demand recording labelled
// high-demand, the worst possible calibration data.

private const val STEP = 7

/**
 * The digits are an on-screen pad rather than a text field. Opening the IME would cover half the
 * screen, animate, and differ by keyboard app — none of which is compatible with the matched visual
 * input the LOW and MEDIUM blocks are held to.
 */
@Composable
fun SerialSubtractionTask(
    onScore: (TaskScore) -> Unit,
    modifier: Modifier = Modifier,
    seed: Int = 0,
) {
    val start = remember(seed) { Random(seed).nextInt(500, 999) }
    var current by remember(seed) { mutableIntStateOf(start) }
    var entry by remember(seed) { mutableStateOf("") }
    var correct by remember(seed) { mutableIntStateOf(0) }
    var attempts by remember(seed) { mutableIntStateOf(0) }

    fun submit() {
        val answer = entry.toIntOrNull() ?: return
        val expected = current - STEP
        attempts++
        if (answer == expected) correct++
        // No right/wrong feedback: an error signal is its own cognitive event, and would land in
        // the middle of the epochs this block is collecting. Always continue from the true value so
        // one slip does not invalidate the rest of the block.
        current = expected
        entry = ""
        onScore(TaskScore(correct = correct, total = attempts))
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$current − $STEP",
            fontSize = 40.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            entry.ifEmpty { "–" },
            fontSize = 36.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(52.dp),
        )

        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
            .forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { key ->
                        OutlinedButton(
                            onClick = {
                                when (key) {
                                    "⌫" -> entry = entry.dropLast(1)
                                    "✓" -> submit()
                                    // Four digits is more than any answer needs and stops a stuck
                                    // finger producing nonsense.
                                    else -> if (entry.length < 4) entry += key
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Text(key, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

        Text(
            stringResource(R.string.cal_task_subtraction_from, start),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
