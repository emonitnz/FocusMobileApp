package com.example.compx551a4.ui.calibration.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.compx551a4.core.TaskScore
import kotlinx.coroutines.delay
import kotlin.random.Random

// [D] HIGH condition, option one: 2-back.
//
// DESIGN.md §2 wants objective confirmation that the user actually engaged. 2-back gives that
// almost for free — it is impossible to score well on while daydreaming, so accuracy doubles as
// proof the block did what it was supposed to.

private const val N = 2
private val LETTERS = listOf('B', 'D', 'F', 'H', 'K', 'M', 'Q', 'R', 'T')

/** 1.5 s per letter: brisk enough to demand attention, still comfortably readable. */
const val DEFAULT_ITEM_MS = 1_500L

/** Roughly this proportion of items are deliberately made matches; the rest are random. */
private const val TARGET_MATCH_RATE = 0.30

/** Internal rather than private so the scoring rules can be unit-tested directly. */
internal class NBackState(private val random: Random) {
    val history = mutableListOf<Char>()
    var current by mutableStateOf(' ')
        private set
    var responded by mutableStateOf(false)
        private set

    var hits by mutableIntStateOf(0)
        private set
    var falseAlarms by mutableIntStateOf(0)
        private set
    var scored by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set

    private var shownAtMs = 0L
    private val reactionTimes = mutableListOf<Long>()

    private val isMatch: Boolean
        get() = history.size > N && history[history.lastIndex] == history[history.lastIndex - N]

    /** Scores the item that just ended, then presents the next one. */
    fun advance(nowMs: Long) {
        if (history.isNotEmpty() && history.size > N) {
            // Only items with N predecessors can be judged, so only those count toward the total.
            scored++
            val wasMatch = isMatch
            when {
                wasMatch && responded -> { hits++; correct++ }
                !wasMatch && !responded -> correct++
                !wasMatch && responded -> falseAlarms++
                // A missed match scores nothing: counted in `scored`, absent from `correct`.
            }
        }

        // Never the same letter twice in a row: a repeat reads as a glitch rather than a new item,
        // and makes the user doubt whether the display advanced. A match is still possible (B D B),
        // and the 2-back candidate can never be the predecessor because of this very rule.
        val previous = history.lastOrNull()
        val next = if (history.size >= N && random.nextDouble() < TARGET_MATCH_RATE) {
            history[history.size - N]
        } else {
            LETTERS.filter { it != previous }.random(random)
        }
        history += next
        current = next
        responded = false
        shownAtMs = nowMs
    }

    fun respond(nowMs: Long) {
        if (responded) return
        responded = true
        reactionTimes += (nowMs - shownAtMs)
    }

    fun score() = TaskScore(
        correct = correct,
        total = scored,
        falseAlarms = falseAlarms,
        meanReactionMs = reactionTimes.average().takeIf { !it.isNaN() },
    )
}

@Composable
fun NBackTask(
    onScore: (TaskScore) -> Unit,
    modifier: Modifier = Modifier,
    itemMs: Long = DEFAULT_ITEM_MS,
    seed: Int = 0,
) {
    val state = remember(seed) { NBackState(Random(seed)) }
    val latestOnScore by rememberUpdatedState(onScore)

    LaunchedEffect(state) {
        while (true) {
            state.advance(System.currentTimeMillis())
            latestOnScore(state.score())
            delay(itemMs)
        }
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Text(
            state.current.toString(),
            fontSize = 96.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(120.dp),
        )
        Button(
            onClick = { state.respond(System.currentTimeMillis()) },
            enabled = !state.responded,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(
                androidx.compose.ui.res.stringResource(com.example.compx551a4.R.string.cal_task_nback_button),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
