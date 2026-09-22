package com.example.compx551a4.ui.calibration.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.compx551a4.R

// [D] MEDIUM condition: reading passage, with a comprehension question afterwards.
//
// The question is asked AFTER the block ends, never during it. Answering involves reading options,
// deciding and tapping — a different cognitive and motor state from reading prose, and epochs
// collected through it would not belong to the MEDIUM condition they are labelled with.

/** One passage plus its question. Three of them, so a repeat calibration is not a memory test. */
data class ReadingPassage(
    val textRes: Int,
    val questionRes: Int,
    val optionsRes: List<Int>,
    val correctIndex: Int,
)

val READING_PASSAGES = listOf(
    ReadingPassage(
        textRes = R.string.cal_passage_1_text,
        questionRes = R.string.cal_passage_1_question,
        optionsRes = listOf(R.string.cal_passage_1_a, R.string.cal_passage_1_b, R.string.cal_passage_1_c),
        correctIndex = 0,
    ),
    ReadingPassage(
        textRes = R.string.cal_passage_2_text,
        questionRes = R.string.cal_passage_2_question,
        optionsRes = listOf(R.string.cal_passage_2_a, R.string.cal_passage_2_b, R.string.cal_passage_2_c),
        correctIndex = 0,
    ),
    ReadingPassage(
        textRes = R.string.cal_passage_3_text,
        questionRes = R.string.cal_passage_3_question,
        optionsRes = listOf(R.string.cal_passage_3_a, R.string.cal_passage_3_b, R.string.cal_passage_3_c),
        correctIndex = 0,
    ),
)

@Composable
fun ReadingTask(passage: ReadingPassage, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(passage.textRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    )
}

/**
 * Shown between blocks. [onAnswer] receives the chosen index, or null for "not sure" — a guess
 * recorded as an answer would overstate engagement, which is the one thing this question exists to
 * measure honestly.
 */
@Composable
fun ReadingQuestion(
    passage: ReadingPassage,
    onAnswer: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.cal_question_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(passage.questionRes), style = MaterialTheme.typography.titleMedium)

        passage.optionsRes.forEachIndexed { index, optionRes ->
            OutlinedButton(
                onClick = { onAnswer(index) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(optionRes))
            }
        }
        TextButton(onClick = { onAnswer(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cal_question_skip))
        }
    }
}
