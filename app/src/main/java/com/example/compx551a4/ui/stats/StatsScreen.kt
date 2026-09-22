package com.example.compx551a4.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.compx551a4.R
import com.example.compx551a4.core.SessionStats
import com.example.compx551a4.ui.common.EmptyState
import com.example.compx551a4.ui.common.ScreenPadding
import com.example.compx551a4.ui.common.SectionCard
import com.example.compx551a4.ui.common.StatTile
import com.example.compx551a4.ui.common.TimeOfDayChart
import com.example.compx551a4.ui.common.formatDateTime
import com.example.compx551a4.ui.common.formatDuration
import com.example.compx551a4.ui.container
import com.example.compx551a4.ui.preview.PreviewData
import com.example.compx551a4.ui.theme.COMPX551A4Theme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// [D] Focus duration, longest streak, notifications deferred, time-of-day pattern.

class StatsViewModel(stats: Flow<SessionStats>) : ViewModel() {
    val uiState: StateFlow<SessionStats> =
        stats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionStats())

    companion object {
        val Factory = viewModelFactory {
            initializer { StatsViewModel(container().sessionRecorder.stats) }
        }
    }
}

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val stats by viewModel.uiState.collectAsStateWithLifecycle()
    StatsContent(stats, modifier)
}

@Composable
private fun StatsContent(stats: SessionStats, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineSmall)

        if (stats.isEmpty) {
            EmptyState(
                title = stringResource(R.string.stats_empty_title),
                body = stringResource(R.string.stats_empty_body),
                icon = Icons.Default.DateRange,
            )
            return@Column
        }

        // Four single numbers, so four stat tiles rather than a chart of four bars.
        SectionCard(
            title = pluralStringResource(R.plurals.stats_sessions, stats.sessions.size, stats.sessions.size),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile(
                    label = stringResource(R.string.stats_total_focus),
                    value = formatDuration(stats.totalFocusedMs),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.stats_longest_streak),
                    value = formatDuration(stats.longestStreakMs),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile(
                    label = stringResource(R.string.stats_deferred),
                    value = stats.totalDeferred.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.stats_delivered),
                    value = stats.totalDelivered.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.stats_time_of_day),
            subtitle = stringResource(R.string.stats_time_of_day_sub),
        ) {
            TimeOfDayChart(stats.focusedMsByHour)
        }

        SectionCard(title = stringResource(R.string.stats_history)) {
            stats.sessions.sortedByDescending { it.startedAtMs }.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatDateTime(session.startedAtMs), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            R.string.stats_session_line,
                            formatDuration(session.focusedMs),
                            session.deferredCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun StatsPreview() {
    COMPX551A4Theme { StatsContent(PreviewData.sampleStats) }
}

@Preview(showBackground = true)
@Composable
private fun StatsEmptyPreview() {
    COMPX551A4Theme { StatsContent(SessionStats()) }
}
